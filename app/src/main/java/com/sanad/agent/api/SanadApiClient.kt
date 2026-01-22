package com.sanad.agent.api

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.sanad.agent.BuildConfig
import com.sanad.agent.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class SanadApiClient(private val context: Context) {
    
    companion object {
        private const val TAG = "SanadAPI"
        
        @Volatile
        private var INSTANCE: SanadApiClient? = null
        
        fun getInstance(context: Context): SanadApiClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SanadApiClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(getServerUrl())
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    private val api = retrofit.create(SanadApi::class.java)
    
    private fun getServerUrl(): String {
        val prefs = context.getSharedPreferences("sanad_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", BuildConfig.SANAD_SERVER_URL) 
            ?: BuildConfig.SANAD_SERVER_URL
    }
    
    fun updateServerUrl(url: String) {
        context.getSharedPreferences("sanad_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server_url", url)
            .apply()
    }
    
    suspend fun submitOrder(order: TrackedOrder): Result<Order> {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            val request = CreateOrderRequest(
                appName = order.appName,
                orderId = order.orderId,
                currentStatus = order.currentStatus,
                eta = dateFormat.format(order.eta),
                restaurantName = order.restaurantName,
                orderTotal = order.orderTotal
            )
            
            val response = api.createOrder(request)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Order submitted successfully: ${response.body()?.id}")
                Result.success(response.body()!!)
            } else {
                Log.e(TAG, "Failed to submit order: ${response.code()}")
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting order", e)
            Result.failure(e)
        }
    }
    
    suspend fun getPendingClaims(): Result<List<PendingClaim>> {
        return try {
            val response = api.getPendingClaims()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pending claims", e)
            Result.failure(e)
        }
    }
    
    suspend fun getPendingEscalations(): Result<List<PendingClaim>> {
        return try {
            val response = api.getPendingEscalations()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pending escalations", e)
            Result.failure(e)
        }
    }
    
    suspend fun markClaimSent(orderId: Int): Result<Order> {
        return try {
            val response = api.markClaimSent(orderId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error marking claim sent", e)
            Result.failure(e)
        }
    }
    
    suspend fun submitSupportReply(orderId: Int, reply: String): Result<Order> {
        return try {
            val response = api.submitSupportReply(orderId, SupportReplyRequest(reply))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting support reply", e)
            Result.failure(e)
        }
    }
    
    suspend fun markSuccess(orderId: Int, amount: Int): Result<Order> {
        return try {
            val response = api.markSuccess(orderId, SuccessRequest(amount))
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error marking success", e)
            Result.failure(e)
        }
    }
    
    suspend fun getStats(): Result<StatsResponse> {
        return try {
            val response = api.getStats()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching stats", e)
            Result.failure(e)
        }
    }
    
    suspend fun getOrders(): Result<List<Order>> {
        return try {
            val response = api.getOrders()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching orders", e)
            Result.failure(e)
        }
    }
    
    // AI Screen Analysis - Send screen text to server for AI processing
    suspend fun analyzeScreen(screenText: String, packageName: String?): Result<AnalyzeScreenResponse> {
        return try {
            val request = AnalyzeScreenRequest(screenText, packageName)
            val response = api.analyzeScreen(request)
            if (response.isSuccessful && response.body() != null) {
                val result = response.body()!!
                if (result.detected) {
                    Log.d(TAG, "AI detected order: ${result.extracted?.orderId} from ${result.extracted?.appName}")
                } else {
                    Log.d(TAG, "AI did not detect order: ${result.reason}")
                }
                Result.success(result)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing screen", e)
            Result.failure(e)
        }
    }
    
    // Network Interception - Send intercepted order data to server
    suspend fun sendInterceptedData(jsonPayload: String): Result<InterceptedDataResponse> {
        return try {
            val request = InterceptedDataRequest(jsonPayload)
            val response = api.sendInterceptedData(request)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Intercepted data sent successfully")
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending intercepted data", e)
            Result.failure(e)
        }
    }
    
    // SSL Proxy - Send parsed order from network interception
    suspend fun sendInterceptedOrder(
        orderId: String,
        restaurantName: String,
        deliveryApp: String,
        status: String,
        eta: String?,
        rawJson: String
    ): Result<Order> {
        return try {
            val request = InterceptedOrderRequest(
                orderId = orderId,
                restaurantName = restaurantName,
                deliveryApp = deliveryApp,
                status = status,
                eta = eta,
                rawJson = rawJson
            )
            val response = api.sendInterceptedOrder(request)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Intercepted order sent: $orderId from $restaurantName")
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending intercepted order", e)
            Result.failure(e)
        }
    }
    
    // SSL Proxy - Send raw intercept data for analysis
    suspend fun sendRawIntercept(
        hostname: String,
        path: String,
        method: String,
        responseBody: String
    ): Result<InterceptedDataResponse> {
        return try {
            val request = RawInterceptRequest(
                hostname = hostname,
                path = path,
                method = method,
                responseBody = responseBody
            )
            val response = api.sendRawIntercept(request)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Raw intercept sent: $method $hostname$path")
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Server error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending raw intercept", e)
            Result.failure(e)
        }
    }
}
