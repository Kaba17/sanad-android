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
import com.sanad.agent.ssl.DeliveryAppParser
import com.sanad.agent.ssl.SanadCertificateManager
import com.sanad.agent.ssl.SanadHttpProxy
import com.sanad.agent.ui.MainActivity
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * SanadVpnService - R&D Network Interception Layer
 * 
 * NOTE: This is an experimental/R&D feature. The production approach for Sanad
 * uses the Accessibility Service (SanadAccessibilityService) which reliably
 * captures screen text and sends it for AI analysis.
 * 
 * Current Limitations:
 * - VPN packet interception requires a userland TCP/IP stack (e.g., tun2socks, lwip)
 *   to properly route TCP traffic to the local HTTP proxy
 * - Without such a stack, the proxy only intercepts when explicitly configured
 * - DNS handling is implemented but TCP routing to proxy is pass-through
 * 
 * For proper MITM interception, consider:
 * 1. Integrating tun2socks library for TUN-to-SOCKS proxy
 * 2. Using device proxy settings (some apps ignore this)
 * 3. Root-based iptables NAT rules
 * 
 * Current Status: Logs packets and handles DNS, but full interception requires
 * manual proxy configuration or additional infrastructure.
 */
class SanadVpnService : VpnService() {
    
    companion object {
        private const val TAG = "SanadVpnService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "sanad_vpn_channel"
        
        var isRunning = false
            private set
        
        fun start(context: Context) {
            val intent = Intent(context, SanadVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, SanadVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        fun isVpnPermissionGranted(context: Context): Boolean {
            return VpnService.prepare(context) == null
        }
        
        const val ACTION_START = "com.sanad.agent.vpn.START"
        const val ACTION_STOP = "com.sanad.agent.vpn.STOP"
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var apiClient: SanadApiClient
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isCapturing = false
    
    private var certificateManager: SanadCertificateManager? = null
    private var httpProxy: SanadHttpProxy? = null
    private var parser: DeliveryAppParser? = null
    
    override fun onCreate() {
        super.onCreate()
        apiClient = SanadApiClient.getInstance(applicationContext)
        createNotificationChannel()
        
        certificateManager = SanadCertificateManager(this)
        parser = DeliveryAppParser()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                if (!isRunning) {
                    startVpn()
                }
            }
            ACTION_STOP -> {
                stopVpn()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                if (!isRunning) {
                    startVpn()
                }
            }
        }
        
        return START_STICKY
    }
    
    private fun startVpn() {
        try {
            if (certificateManager?.initialize() != true) {
                Log.e(TAG, "Failed to initialize certificate manager")
                stopSelf()
                return
            }
            
            httpProxy = SanadHttpProxy(this, certificateManager!!) { interceptedData ->
                handleInterceptedData(interceptedData)
            }
            
            if (!httpProxy!!.start()) {
                Log.e(TAG, "Failed to start HTTP proxy")
                stopSelf()
                return
            }
            
            val builder = Builder()
                .setSession("Sanad Consumer Rights")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)
            
            val excludedApps = listOf(
                packageName,
                "com.android.vending",
                "com.google.android.gms"
            )
            
            for (app in excludedApps) {
                try {
                    builder.addDisallowedApplication(app)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not exclude app: $app")
                }
            }
            
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                isRunning = true
                isCapturing = true
                Log.d(TAG, "VPN started successfully with SSL interception")
                
                serviceScope.launch {
                    capturePackets()
                }
            } else {
                Log.e(TAG, "Failed to establish VPN")
                httpProxy?.stop()
                stopSelf()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            httpProxy?.stop()
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
            if (isCapturing) {
                Log.e(TAG, "Error capturing packets", e)
            }
        } finally {
            try {
                inputStream.close()
                outputStream.close()
            } catch (e: Exception) {}
        }
    }
    
    private fun processPacket(buffer: ByteBuffer, outputStream: FileOutputStream) {
        try {
            val data = buffer.array()
            val length = buffer.limit()
            
            if (length < 20) {
                return
            }
            
            val version = (data[0].toInt() shr 4) and 0xF
            if (version != 4) {
                return
            }
            
            val protocol = data[9].toInt() and 0xFF
            val ipHeaderLength = (data[0].toInt() and 0xF) * 4
            
            when (protocol) {
                6 -> processTcpPacket(data, length, ipHeaderLength, outputStream)
                17 -> processUdpPacket(data, length, ipHeaderLength, outputStream)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
        }
    }
    
    private fun processTcpPacket(data: ByteArray, length: Int, ipHeaderLength: Int, outputStream: FileOutputStream) {
        if (length < ipHeaderLength + 20) return
        
        val destPort = ((data[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                       (data[ipHeaderLength + 3].toInt() and 0xFF)
        
        val destIp = "${data[16].toInt() and 0xFF}.${data[17].toInt() and 0xFF}." +
                     "${data[18].toInt() and 0xFF}.${data[19].toInt() and 0xFF}"
        
        if (destPort == 80 || destPort == 443) {
            Log.v(TAG, "TCP -> $destIp:$destPort (${length} bytes)")
        }
        
        outputStream.write(data, 0, length)
    }
    
    private fun processUdpPacket(data: ByteArray, length: Int, ipHeaderLength: Int, outputStream: FileOutputStream) {
        if (length < ipHeaderLength + 8) return
        
        val destPort = ((data[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                       (data[ipHeaderLength + 3].toInt() and 0xFF)
        
        if (destPort == 53) {
            serviceScope.launch {
                handleDnsQuery(data, length, ipHeaderLength, outputStream)
            }
        } else {
            outputStream.write(data, 0, length)
        }
    }
    
    private suspend fun handleDnsQuery(data: ByteArray, length: Int, ipHeaderLength: Int, outputStream: FileOutputStream) {
        try {
            val udpHeaderLength = 8
            val dnsDataStart = ipHeaderLength + udpHeaderLength
            val dnsData = data.copyOfRange(dnsDataStart, length)
            
            // Extract source/dest info from original packet for response
            val srcIp = ByteArray(4)
            val dstIp = ByteArray(4)
            System.arraycopy(data, 12, srcIp, 0, 4) // Source IP
            System.arraycopy(data, 16, dstIp, 0, 4) // Dest IP
            
            val srcPort = ((data[ipHeaderLength].toInt() and 0xFF) shl 8) or
                          (data[ipHeaderLength + 1].toInt() and 0xFF)
            
            val dnsSocket = DatagramSocket()
            protect(dnsSocket)
            
            val dnsPacket = DatagramPacket(
                dnsData,
                dnsData.size,
                InetAddress.getByName("8.8.8.8"),
                53
            )
            
            withContext(Dispatchers.IO) {
                dnsSocket.send(dnsPacket)
            }
            
            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            
            dnsSocket.soTimeout = 5000
            withContext(Dispatchers.IO) {
                dnsSocket.receive(responsePacket)
            }
            
            val dnsResponse = responseBuffer.copyOf(responsePacket.length)
            dnsSocket.close()
            
            // Craft response IP packet
            // This is a simplified version - a full implementation would calculate checksums
            val responseLength = 20 + 8 + dnsResponse.size // IP + UDP + DNS
            val response = ByteArray(responseLength)
            
            // IP Header (simplified - no checksum calculation)
            response[0] = 0x45.toByte() // Version 4, IHL 5
            response[1] = 0x00.toByte() // DSCP/ECN
            response[2] = ((responseLength shr 8) and 0xFF).toByte() // Total length (high)
            response[3] = (responseLength and 0xFF).toByte() // Total length (low)
            response[4] = 0x00.toByte() // Identification
            response[5] = 0x00.toByte()
            response[6] = 0x40.toByte() // Flags: Don't fragment
            response[7] = 0x00.toByte() // Fragment offset
            response[8] = 0x40.toByte() // TTL
            response[9] = 0x11.toByte() // Protocol: UDP
            response[10] = 0x00.toByte() // Header checksum (calculated by kernel)
            response[11] = 0x00.toByte()
            // Source = original destination (DNS server), Dest = original source
            System.arraycopy(dstIp, 0, response, 12, 4) // Swap IPs
            System.arraycopy(srcIp, 0, response, 16, 4)
            
            // UDP Header
            response[20] = 0x00.toByte() // Source port: 53 (high)
            response[21] = 0x35.toByte() // Source port: 53 (low)
            response[22] = ((srcPort shr 8) and 0xFF).toByte() // Dest port (high)
            response[23] = (srcPort and 0xFF).toByte() // Dest port (low)
            val udpLength = 8 + dnsResponse.size
            response[24] = ((udpLength shr 8) and 0xFF).toByte() // UDP length (high)
            response[25] = (udpLength and 0xFF).toByte() // UDP length (low)
            response[26] = 0x00.toByte() // Checksum (optional for IPv4)
            response[27] = 0x00.toByte()
            
            // DNS Response data
            System.arraycopy(dnsResponse, 0, response, 28, dnsResponse.size)
            
            // Write response back to TUN
            withContext(Dispatchers.IO) {
                outputStream.write(response)
                outputStream.flush()
            }
            
            Log.v(TAG, "DNS query resolved and response sent (${dnsResponse.size} bytes)")
            
        } catch (e: Exception) {
            Log.e(TAG, "DNS handling error", e)
        }
    }
    
    private fun handleInterceptedData(data: SanadHttpProxy.InterceptedData) {
        serviceScope.launch {
            try {
                Log.i(TAG, "Intercepted: ${data.method} ${data.hostname}${data.path}")
                
                val parsedOrder = parser?.parseInterceptedData(data)
                
                if (parsedOrder != null) {
                    Log.i(TAG, "Found order: ${parsedOrder.orderId} from ${parsedOrder.restaurantName}")
                    
                    apiClient.sendInterceptedOrder(
                        orderId = parsedOrder.orderId,
                        restaurantName = parsedOrder.restaurantName,
                        deliveryApp = parsedOrder.deliveryApp,
                        status = parsedOrder.orderStatus,
                        eta = parsedOrder.eta,
                        rawJson = parsedOrder.rawJson
                    )
                } else {
                    apiClient.sendRawIntercept(
                        hostname = data.hostname,
                        path = data.path,
                        method = data.method,
                        responseBody = data.responseBody
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling intercepted data", e)
            }
        }
    }
    
    private fun stopVpn() {
        isCapturing = false
        isRunning = false
        
        httpProxy?.stop()
        httpProxy = null
        
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        
        serviceScope.cancel()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d(TAG, "SanadVpnService stopped")
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
        
        val stopIntent = Intent(this, SanadVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("سند VPN نشط")
            .setContentText("يراقب تطبيقات التوصيل بتشفير SSL...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, "إيقاف", stopPendingIntent)
            .build()
    }
    
    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
    
    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
    
    fun getCertificateManager(): SanadCertificateManager? = certificateManager
}
