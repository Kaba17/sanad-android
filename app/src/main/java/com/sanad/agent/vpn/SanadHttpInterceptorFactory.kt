package com.sanad.agent.vpn

import android.util.Log
import com.github.AgitoXIV.netbare.http.HttpInterceptor
import com.github.AgitoXIV.netbare.http.HttpInterceptorFactory
import com.github.AgitoXIV.netbare.http.HttpRequest
import com.github.AgitoXIV.netbare.http.HttpRequestChain
import com.github.AgitoXIV.netbare.http.HttpResponse
import com.github.AgitoXIV.netbare.http.HttpResponseChain
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SanadHttpInterceptorFactory(
    private val deliveryInterceptor: DeliveryAppInterceptor
) : HttpInterceptorFactory {
    
    override fun create(): HttpInterceptor {
        return SanadHttpInterceptor(deliveryInterceptor)
    }
}

class SanadHttpInterceptor(
    private val deliveryInterceptor: DeliveryAppInterceptor
) : HttpInterceptor {
    
    companion object {
        private const val TAG = "SanadHttpInterceptor"
    }
    
    private var currentHost: String? = null
    private var currentPath: String? = null
    private var shouldCapture = false
    private var responseBuffer: ByteArrayOutputStream? = null
    private var contentType: String? = null
    private var statusCode: Int = 0
    
    override fun intercept(chain: HttpRequestChain, buffer: ByteBuffer) {
        val request = chain.request()
        
        currentHost = request.host()
        currentPath = request.requestUri()
        shouldCapture = deliveryInterceptor.shouldIntercept(currentHost ?: "", currentPath ?: "")
        
        if (shouldCapture) {
            Log.d(TAG, "Will capture response from: $currentHost$currentPath")
            responseBuffer = ByteArrayOutputStream()
        }
        
        chain.process(buffer)
    }
    
    override fun intercept(chain: HttpResponseChain, buffer: ByteBuffer) {
        val response = chain.response()
        
        if (shouldCapture && responseBuffer != null) {
            if (statusCode == 0) {
                statusCode = response.code()
                contentType = response.header("Content-Type")
            }
            
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            responseBuffer?.write(bytes)
            
            buffer.rewind()
        }
        
        chain.process(buffer)
    }
    
    override fun onRequestFinished(request: HttpRequest) {
    }
    
    override fun onResponseFinished(response: HttpResponse) {
        if (shouldCapture && responseBuffer != null) {
            try {
                val bodyBytes = responseBuffer?.toByteArray() ?: return
                if (bodyBytes.isNotEmpty()) {
                    Log.d(TAG, "Captured ${bodyBytes.size} bytes from $currentHost$currentPath")
                    deliveryInterceptor.interceptResponse(
                        currentHost ?: "",
                        currentPath ?: "",
                        statusCode,
                        contentType,
                        bodyBytes
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing response", e)
            } finally {
                reset()
            }
        }
    }
    
    private fun reset() {
        currentHost = null
        currentPath = null
        shouldCapture = false
        responseBuffer = null
        contentType = null
        statusCode = 0
    }
}
