package com.sanad.agent.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.util.OrderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NotificationAction"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra("order_id") ?: return
        val serverOrderId = intent.getIntExtra("server_order_id", -1)
        
        val orderManager = OrderManager.getInstance(context)
        val apiClient = SanadApiClient.getInstance(context)
        
        when (intent.action) {
            "com.sanad.agent.ACTION_APPROVE_CLAIM" -> {
                Log.d(TAG, "User approved claim for order: $orderId")
                
                // Update local order
                val order = orderManager.getOrderById(orderId)
                if (order != null) {
                    order.userApproved = true
                    orderManager.updateOrder(order)
                    
                    // Prepare auto-paste
                    order.complaintText?.let { complaint ->
                        SanadAccessibilityService.instance?.setPendingPaste(
                            complaint,
                            order.appPackage
                        )
                    }
                    
                    // Open the delivery app's chat
                    openDeliveryAppChat(context, order.appPackage)
                }
                
                // Dismiss notification
                dismissNotification(context, orderId.hashCode() + 2000)
            }
            
            "com.sanad.agent.ACTION_DISMISS_CLAIM" -> {
                Log.d(TAG, "User dismissed claim for order: $orderId")
                
                // Just dismiss the notification
                dismissNotification(context, orderId.hashCode() + 2000)
            }
        }
    }
    
    private fun openDeliveryAppChat(context: Context, packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening delivery app", e)
        }
    }
    
    private fun dismissNotification(context: Context, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
}
