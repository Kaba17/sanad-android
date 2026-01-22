package com.sanad.agent.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sanad.agent.R
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class SanadVpnService : VpnService() {
    
    companion object {
        private const val TAG = "SanadVpnService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sanad_vpn_channel"
        
        var isRunning = false
            private set
        
        fun start(context: Context) {
            val intent = Intent(context, SanadVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, SanadVpnService::class.java))
        }
        
        fun isVpnPermissionGranted(context: Context): Boolean {
            return VpnService.prepare(context) == null
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var apiClient: SanadApiClient
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isCapturing = false
    
    override fun onCreate() {
        super.onCreate()
        apiClient = SanadApiClient.getInstance(applicationContext)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        if (!isRunning) {
            startVpn()
        }
        
        return START_STICKY
    }
    
    private fun startVpn() {
        try {
            vpnInterface = Builder()
                .setSession("Sanad VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .establish()
            
            if (vpnInterface != null) {
                isRunning = true
                isCapturing = true
                Log.d(TAG, "VPN started successfully")
                
                serviceScope.launch {
                    capturePackets()
                }
            } else {
                Log.e(TAG, "Failed to establish VPN")
                stopSelf()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSelf()
        }
    }
    
    private suspend fun capturePackets() = withContext(Dispatchers.IO) {
        val vpnFd = vpnInterface ?: return@withContext
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)
        
        try {
            while (isCapturing && isRunning) {
                buffer.clear()
                val length = inputStream.read(buffer.array())
                
                if (length > 0) {
                    buffer.limit(length)
                    processPacket(buffer, outputStream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing packets", e)
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }
    
    private fun processPacket(buffer: ByteBuffer, outputStream: FileOutputStream) {
        try {
            // Forward packet as-is (transparent proxy)
            // In a full implementation, we would:
            // 1. Parse IP/TCP headers
            // 2. Extract HTTP/HTTPS data
            // 3. For HTTPS: perform MITM with our CA certificate
            // 4. Filter for delivery app domains
            // 5. Extract order data and send to server
            
            val data = ByteArray(buffer.limit())
            buffer.get(data)
            outputStream.write(data)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سند - مراقبة الشبكة",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار خدمة VPN لمراقبة بيانات الطلبات"
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
            .setContentText("نراقب بيانات تطبيقات التوصيل")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        isRunning = false
        vpnInterface?.close()
        serviceScope.cancel()
        Log.d(TAG, "SanadVpnService destroyed")
    }
}
