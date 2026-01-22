package com.sanad.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sanad.agent.R
import com.sanad.agent.ssl.DeliveryAppParser
import com.sanad.agent.ssl.SanadCertificateManager
import com.sanad.agent.ssl.SanadHttpProxy
import com.sanad.agent.ui.CertificateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SanadProxyService : Service() {
    
    companion object {
        private const val TAG = "SanadProxyService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "sanad_proxy_channel"
        
        const val ACTION_START = "com.sanad.agent.proxy.START"
        const val ACTION_STOP = "com.sanad.agent.proxy.STOP"
        const val ACTION_STATUS_CHANGED = "com.sanad.agent.proxy.STATUS_CHANGED"
        const val ACTION_ERROR = "com.sanad.agent.proxy.ERROR"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        
        @Volatile
        var isRunning = false
            private set
        
        fun start(context: Context) {
            val intent = Intent(context, SanadProxyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, SanadProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpProxy: SanadHttpProxy? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): SanadProxyService = this@SanadProxyService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_STICKY
    }
    
    private fun startProxy() {
        if (isRunning) {
            Log.w(TAG, "Proxy already running")
            return
        }
        
        val serverUrl = getSharedPreferences("sanad_prefs", MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "Server URL not configured")
            broadcastError("يجب ضبط رابط الخادم أولاً")
            stopSelf()
            return
        }
        
        if (!isPortAvailable(8888)) {
            Log.e(TAG, "Port 8888 already in use")
            broadcastError("المنفذ 8888 مستخدم بالفعل")
            stopSelf()
            return
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true
        broadcastStatus(true)
        
        serviceScope.launch {
            try {
                val certManager = SanadCertificateManager(this@SanadProxyService)
                certManager.initialize()
                
                httpProxy = SanadHttpProxy(
                    context = this@SanadProxyService,
                    certificateManager = certManager,
                    interceptCallback = { interceptedData ->
                        handleInterceptedData(interceptedData, serverUrl)
                    }
                )
                httpProxy?.start()
                
                Log.i(TAG, "Proxy started on port 8888")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                isRunning = false
                broadcastError("فشل تشغيل Proxy: ${e.message}")
                broadcastStatus(false)
                stopSelf()
            }
        }
    }
    
    private fun handleInterceptedData(data: SanadHttpProxy.InterceptedData, serverUrl: String) {
        serviceScope.launch {
            try {
                val parser = DeliveryAppParser()
                val parsedOrder = parser.parseInterceptedData(data)
                
                if (parsedOrder != null) {
                    Log.i(TAG, "Extracted order: ${parsedOrder.orderId} from ${parsedOrder.deliveryApp}")
                    sendToServer(serverUrl, parsedOrder)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing intercepted data", e)
            }
        }
    }
    
    private suspend fun sendToServer(serverUrl: String, order: DeliveryAppParser.ParsedOrder) {
        try {
            val url = java.net.URL("$serverUrl/api/intercept/order")
            val connection = withContext(Dispatchers.IO) {
                url.openConnection() as java.net.HttpURLConnection
            }
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val escapedJson = order.rawJson
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            val payload = """
                {
                    "orderId": "${order.orderId}",
                    "source": "${order.deliveryApp}",
                    "restaurantName": "${order.restaurantName}",
                    "status": "${order.orderStatus}",
                    "eta": ${order.eta?.let { "\"$it\"" } ?: "null"},
                    "totalAmount": ${order.totalAmount ?: "null"},
                    "rawData": "$escapedJson"
                }
            """.trimIndent()
            
            withContext(Dispatchers.IO) {
                connection.outputStream.use { it.write(payload.toByteArray()) }
            }
            
            val responseCode = connection.responseCode
            Log.i(TAG, "Sent order to server, response: $responseCode")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send order to server", e)
        }
    }
    
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            java.net.ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun broadcastStatus(running: Boolean) {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_RUNNING, running)
        }
        sendBroadcast(intent)
    }
    
    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        sendBroadcast(intent)
    }
    
    private fun stopProxy() {
        Log.i(TAG, "Stopping proxy...")
        
        try {
            httpProxy?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy", e)
        }
        
        httpProxy = null
        isRunning = false
        broadcastStatus(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سند Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار خدمة Proxy لاعتراض البيانات"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CertificateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, SanadProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("سند Proxy نشط")
            .setContentText("المنفذ: 8888 | اضبط الواي فاي للاتصال")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "إيقاف", stopPendingIntent)
            .build()
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        httpProxy?.stop()
        isRunning = false
        super.onDestroy()
    }
}
