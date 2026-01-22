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
import com.sanad.agent.ssl.SanadCertificateManager
import com.sanad.agent.ssl.SanadHttpProxy
import com.sanad.agent.ui.CertificateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SanadProxyService : Service() {
    
    companion object {
        private const val TAG = "SanadProxyService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "sanad_proxy_channel"
        
        const val ACTION_START = "com.sanad.agent.proxy.START"
        const val ACTION_STOP = "com.sanad.agent.proxy.STOP"
        
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
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        serviceScope.launch {
            try {
                val serverUrl = getSharedPreferences("sanad_prefs", MODE_PRIVATE)
                    .getString("server_url", "") ?: ""
                
                if (serverUrl.isEmpty()) {
                    Log.e(TAG, "Server URL not configured")
                    stopSelf()
                    return@launch
                }
                
                val certManager = SanadCertificateManager(this@SanadProxyService)
                certManager.initialize()
                
                httpProxy = SanadHttpProxy(certManager, serverUrl)
                httpProxy?.start()
                
                isRunning = true
                Log.i(TAG, "Proxy started on port 8888")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                isRunning = false
                stopSelf()
            }
        }
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
