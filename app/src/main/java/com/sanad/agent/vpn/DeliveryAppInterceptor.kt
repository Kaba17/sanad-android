package com.sanad.agent.vpn

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sanad.agent.api.SanadApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.charset.Charset

class DeliveryAppInterceptor(
    private val apiClient: SanadApiClient,
    private val scope: CoroutineScope
) {
    
    companion object {
        private const val TAG = "DeliveryInterceptor"
        
        private val TARGET_DOMAINS = listOf(
            "hungerstation.com",
            "api.hungerstation.com",
            "jahez.net",
            "api.jahez.net",
            "jahez.com",
            "mrsool.co",
            "api.mrsool.co",
            "toyou.io",
            "api.toyou.io",
            "careem.com",
            "food.careem.com"
        )
        
        private val ORDER_API_PATTERNS = listOf(
            Regex("""/orders?/\d+"""),
            Regex("""/order[_-]?details?"""),
            Regex("""/tracking"""),
            Regex("""/delivery[_-]?status"""),
            Regex("""/v\d+/orders?"""),
            Regex("""/api/.*order.*""", RegexOption.IGNORE_CASE)
        )
    }
    
    private val gson = Gson()
    private val parser = OrderDataParser()
    private val sensitiveFilter = SensitiveDataFilter()
    
    fun shouldIntercept(host: String, path: String): Boolean {
        val isTargetDomain = TARGET_DOMAINS.any { domain ->
            host.endsWith(domain) || host == domain
        }
        
        if (!isTargetDomain) return false
        
        val isOrderApi = ORDER_API_PATTERNS.any { pattern ->
            pattern.containsMatchIn(path)
        }
        
        Log.d(TAG, "Checking: $host$path -> domain=$isTargetDomain, orderApi=$isOrderApi")
        return isOrderApi
    }
    
    fun interceptResponse(
        host: String,
        path: String,
        statusCode: Int,
        contentType: String?,
        responseBody: ByteArray
    ) {
        if (statusCode !in 200..299) return
        if (contentType?.contains("json") != true) return
        
        try {
            val bodyString = String(responseBody, Charset.forName("UTF-8"))
            if (bodyString.isBlank()) return
            
            Log.d(TAG, "Intercepted from $host$path (${bodyString.length} bytes)")
            
            val jsonElement = JsonParser.parseString(bodyString)
            if (!jsonElement.isJsonObject && !jsonElement.isJsonArray) return
            
            val appName = detectAppFromHost(host)
            val orderData = parser.extractOrderData(jsonElement, appName)
            
            if (orderData != null) {
                val sanitizedData = sensitiveFilter.sanitize(orderData)
                sendToServer(host, path, appName, sanitizedData)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing response", e)
        }
    }
    
    private fun detectAppFromHost(host: String): String {
        return when {
            host.contains("hungerstation") -> "Hungerstation"
            host.contains("jahez") -> "Jahez"
            host.contains("mrsool") -> "Mrsool"
            host.contains("toyou") -> "ToYou"
            host.contains("careem") -> "Careem"
            else -> "Unknown"
        }
    }
    
    private fun sendToServer(host: String, path: String, appName: String, orderData: JsonObject) {
        scope.launch {
            try {
                val payload = JsonObject().apply {
                    addProperty("source", "network_intercept")
                    addProperty("host", host)
                    addProperty("path", path)
                    addProperty("appName", appName)
                    addProperty("timestamp", System.currentTimeMillis())
                    add("orderData", orderData)
                }
                
                val result = apiClient.sendInterceptedData(payload.toString())
                result.onSuccess {
                    Log.d(TAG, "Successfully sent intercepted order to server")
                }
                result.onFailure { error ->
                    Log.e(TAG, "Failed to send to server: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to server", e)
            }
        }
    }
}
