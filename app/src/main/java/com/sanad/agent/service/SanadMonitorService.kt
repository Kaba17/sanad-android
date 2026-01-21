package com.sanad.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sanad.agent.R
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.ui.MainActivity
import com.sanad.agent.util.OrderManager
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class SanadMonitorService : Service() {
    
    companion object {
        private const val TAG = "SanadMonitor"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sanad_monitor_channel"
        private const val DELAY_CHECK_INTERVAL_MS = 60_000L // Check every minute
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var orderManager: OrderManager
    private lateinit var apiClient: SanadApiClient
    
    override fun onCreate() {
        super.onCreate()
        orderManager = OrderManager.getInstance(applicationContext)
        apiClient = SanadApiClient.getInstance(applicationContext)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startMonitoring()
        return START_STICKY
    }
    
    private fun startMonitoring() {
        serviceScope.launch {
            while (isActive) {
                try {
                    checkForDelays()
                    checkForPendingClaims()
                    delay(DELAY_CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop", e)
                }
            }
        }
    }
    
    private suspend fun checkForDelays() {
        val trackedOrders = orderManager.getActiveOrders()
        
        for (order in trackedOrders) {
            if (order.checkDelay() && !order.userNotified) {
                // Order is delayed - submit to server and notify user
                Log.d(TAG, "Order ${order.orderId} is delayed by ${order.delayMinutes} minutes")
                
                // Submit to server for AI complaint generation
                val result = apiClient.submitOrder(order)
                result.onSuccess { serverOrder ->
                    order.serverOrderId = serverOrder.id
                    orderManager.updateOrder(order)
                    
                    // Wait for complaint to be generated, then notify user
                    delay(5000) // Give server time to generate complaint
                    
                    val claims = apiClient.getPendingClaims()
                    claims.onSuccess { pendingClaims ->
                        val claim = pendingClaims.find { it.orderId == order.orderId }
                        if (claim != null) {
                            order.complaintText = claim.complaintText
                            orderManager.updateOrder(order)
                            showDelayNotification(order, claim.complaintText)
                        }
                    }
                }
            }
        }
    }
    
    private suspend fun checkForPendingClaims() {
        // Check for any claims ready to send
        val claims = apiClient.getPendingClaims()
        claims.onSuccess { pendingClaims ->
            for (claim in pendingClaims) {
                val order = orderManager.getOrderByServerId(claim.id)
                if (order != null && order.userApproved && !order.claimSent) {
                    // User approved - prepare for auto-paste
                    SanadAccessibilityService.instance?.setPendingPaste(
                        claim.complaintText,
                        order.appPackage
                    )
                }
            }
        }
        
        // Also check for escalations
        val escalations = apiClient.getPendingEscalations()
        escalations.onSuccess { pendingEscalations ->
            for (escalation in pendingEscalations) {
                val order = orderManager.getOrderByServerId(escalation.id)
                if (order != null) {
                    showEscalationNotification(order, escalation.complaintText)
                }
            }
        }
    }
    
    private fun showDelayNotification(order: com.sanad.agent.model.TrackedOrder, complaintText: String) {
        val approveIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "com.sanad.agent.ACTION_APPROVE_CLAIM"
            putExtra("order_id", order.orderId)
            putExtra("server_order_id", order.serverOrderId)
        }
        val approvePending = PendingIntent.getBroadcast(
            this, order.orderId.hashCode(), approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val dismissIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = "com.sanad.agent.ACTION_DISMISS_CLAIM"
            putExtra("order_id", order.orderId)
        }
        val dismissPending = PendingIntent.getBroadcast(
            this, order.orderId.hashCode() + 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "sanad_claims_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("طلبك تأخر ${order.getDelayDescription()}")
            .setContentText("من ${order.appName} - هل تريد المطالبة بتعويض؟")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("طلبك #${order.orderId} من ${order.appName} تأخر ${order.getDelayDescription()} عن الوقت المتوقع.\n\nهل تريدني أن أطالب بتعويض نيابة عنك؟"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_check, "نعم، طالب بتعويض", approvePending)
            .addAction(R.drawable.ic_close, "لا شكراً", dismissPending)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(order.orderId.hashCode() + 2000, notification)
        
        order.userNotified = true
        orderManager.updateOrder(order)
    }
    
    private fun showEscalationNotification(order: com.sanad.agent.model.TrackedOrder, counterComplaint: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("order_id", order.orderId)
        }
        val openPending = PendingIntent.getActivity(
            this, order.orderId.hashCode() + 3000, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "sanad_claims_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("رد ضعيف! جاهز للتصعيد")
            .setContentText("${order.appName} رد برد ضعيف - رد التصعيد جاهز")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("رد ${order.appName} ضعيف ولا يتناسب مع حقوقك.\n\nجهزت لك رد تصعيدي أقوى للمطالبة بتعويض عادل."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(order.orderId.hashCode() + 4000, notification)
    }
    
    private fun createNotificationChannel() {
        // Monitor channel (persistent)
        val monitorChannel = NotificationChannel(
            CHANNEL_ID,
            "مراقبة الطلبات",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "يراقب طلبات التوصيل بالخلفية"
        }
        
        // Claims channel (high priority)
        val claimsChannel = NotificationChannel(
            "sanad_claims_channel",
            "التعويضات",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعارات التأخير والتعويضات"
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(monitorChannel)
        notificationManager.createNotificationChannel(claimsChannel)
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("سند يراقب طلباتك")
            .setContentText("سيتم إبلاغك فوراً عند تأخر أي طلب")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
