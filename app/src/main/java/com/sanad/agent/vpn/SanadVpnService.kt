package com.sanad.agent.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.AgitoXIV.netbare.NetBare
import com.github.AgitoXIV.netbare.NetBareConfig
import com.github.AgitoXIV.netbare.NetBareListener
import com.github.AgitoXIV.netbare.ssl.JKS
import com.sanad.agent.R
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File

class SanadVpnService : VpnService(), NetBareListener {
    
    companion object {
        private const val TAG = "SanadVpnService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sanad_vpn_channel"
        
        var isRunning = false
            private set
        
        private var netBare: NetBare? = null
        
        fun start(context: Context) {
            val intent = Intent(context, SanadVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            netBare?.stop()
            context.stopService(Intent(context, SanadVpnService::class.java))
        }
        
        fun isVpnPermissionGranted(context: Context): Boolean {
            return VpnService.prepare(context) == null
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var apiClient: SanadApiClient
    private lateinit var interceptor: DeliveryAppInterceptor
    
    override fun onCreate() {
        super.onCreate()
        apiClient = SanadApiClient.getInstance(applicationContext)
        interceptor = DeliveryAppInterceptor(apiClient, serviceScope)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (!isRunning) {
            startNetBare()
        }
        
        return START_STICKY
    }
    
    private fun startNetBare() {
        try {
            val jks = createOrLoadJKS()
            if (jks == null) {
                Log.e(TAG, "Failed to create/load JKS certificate")
                stopSelf()
                return
            }
            
            val config = NetBareConfig.defaultHttpConfig(
                jks,
                interceptorFactories()
            )
            
            netBare = NetBare.get().apply {
                attachApplication(application, false)
                registerNetBareListener(this@SanadVpnService)
            }
            
            netBare?.start(config)
            isRunning = true
            Log.d(TAG, "NetBare VPN started successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NetBare", e)
            stopSelf()
        }
    }
    
    private fun createOrLoadJKS(): JKS? {
        return try {
            val keyStoreFile = File(filesDir, "sanad_keystore.jks")
            val alias = "SanadCA"
            val password = "sanad_secure_pass"
            
            if (!keyStoreFile.exists()) {
                Log.d(TAG, "Generating new SSL certificate...")
                JKS.create(
                    keyStoreFile,
                    alias,
                    password.toCharArray(),
                    "Sanad Consumer Rights Agent",
                    "Sanad",
                    "Riyadh",
                    "SA"
                )
            }
            
            JKS(keyStoreFile, alias, password.toCharArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating JKS", e)
            null
        }
    }
    
    private fun interceptorFactories(): List<Any> {
        return listOf(interceptor)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سند - اعتراض الشبكة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار خدمة VPN لاعتراض بيانات الطلبات"
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
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("سند يراقب الشبكة")
            .setContentText("نعترض بيانات تطبيقات التوصيل")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onServiceStarted() {
        Log.d(TAG, "NetBare service started")
        isRunning = true
    }
    
    override fun onServiceStopped() {
        Log.d(TAG, "NetBare service stopped")
        isRunning = false
    }
    
    override fun onDestroy() {
        super.onDestroy()
        netBare?.stop()
        serviceScope.cancel()
        isRunning = false
        Log.d(TAG, "SanadVpnService destroyed")
    }
}
