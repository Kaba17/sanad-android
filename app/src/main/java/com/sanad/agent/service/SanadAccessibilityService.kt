package com.sanad.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sanad.agent.model.DeliveryApps
import com.sanad.agent.model.TrackedOrder
import com.sanad.agent.util.OrderManager
import java.text.SimpleDateFormat
import java.util.*

class SanadAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SanadAccessibility"
        var instance: SanadAccessibilityService? = null
            private set
        
        var isServiceRunning = false
            private set
    }
    
    private lateinit var orderManager: OrderManager
    private var pendingPasteText: String? = null
    private var targetChatPackage: String? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        
        orderManager = OrderManager.getInstance(applicationContext)
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            packageNames = DeliveryApps.getSupportedPackageNames().toTypedArray()
        }
        serviceInfo = info
        
        Log.d(TAG, "Sanad Accessibility Service connected and monitoring delivery apps")
        
        // Start foreground monitor service
        val serviceIntent = Intent(this, SanadMonitorService::class.java)
        startForegroundService(serviceIntent)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        val packageName = event.packageName?.toString() ?: return
        val appConfig = DeliveryApps.getByPackageName(packageName) ?: return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Check if we need to auto-paste complaint
                if (shouldAutoPaste(packageName, event)) {
                    autoPasteComplaint()
                } else {
                    // Read and parse screen content
                    val rootNode = rootInActiveWindow ?: return
                    parseScreenContent(rootNode, appConfig)
                    rootNode.recycle()
                }
            }
        }
    }
    
    private fun parseScreenContent(rootNode: AccessibilityNodeInfo, appConfig: com.sanad.agent.model.DeliveryAppConfig) {
        val screenText = extractAllText(rootNode)
        
        // Try to extract order information
        var orderId: String? = null
        var eta: Date? = null
        var status = "monitoring"
        var restaurantName: String? = null
        
        // Extract order ID
        for (pattern in appConfig.orderIdPatterns) {
            val match = pattern.find(screenText)
            if (match != null) {
                orderId = match.groupValues.getOrNull(1) ?: match.value
                break
            }
        }
        
        // Extract ETA
        for (pattern in appConfig.etaPatterns) {
            val match = pattern.find(screenText)
            if (match != null) {
                eta = parseEta(match, screenText)
                if (eta != null) break
            }
        }
        
        // Determine status
        for ((statusKey, patterns) in appConfig.statusPatterns) {
            for (pattern in patterns) {
                if (pattern.containsMatchIn(screenText)) {
                    status = statusKey
                    break
                }
            }
        }
        
        // Extract restaurant name (simple heuristic)
        val restaurantPatterns = listOf(
            Regex("""من\s+(.+?)(?:\s|$)"""),
            Regex("""طلبك من\s+(.+?)(?:\s|$)"""),
            Regex("""Order from\s+(.+?)(?:\s|$)""")
        )
        for (pattern in restaurantPatterns) {
            val match = pattern.find(screenText)
            if (match != null) {
                restaurantName = match.groupValues.getOrNull(1)?.take(50)
                break
            }
        }
        
        // If we found a valid order, track it
        if (orderId != null && eta != null) {
            val trackedOrder = TrackedOrder(
                orderId = orderId,
                appName = appConfig.appName,
                appPackage = appConfig.packageName,
                restaurantName = restaurantName,
                eta = eta,
                currentStatus = status
            )
            
            orderManager.trackOrder(trackedOrder)
            Log.d(TAG, "Tracking order: $orderId from ${appConfig.appName}, ETA: $eta")
        }
    }
    
    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val builder = StringBuilder()
        extractTextRecursive(node, builder)
        return builder.toString()
    }
    
    private fun extractTextRecursive(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.let { builder.append(it).append(" ") }
        node.contentDescription?.let { builder.append(it).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractTextRecursive(child, builder)
            child.recycle()
        }
    }
    
    private fun parseEta(match: MatchResult, fullText: String): Date? {
        return try {
            val calendar = Calendar.getInstance()
            
            // Check if it's a "within X minutes" format
            val minutesMatch = Regex("""(\d+)\s*(دقيقة|min)""").find(match.value)
            if (minutesMatch != null) {
                val minutes = minutesMatch.groupValues[1].toInt()
                calendar.add(Calendar.MINUTE, minutes)
                return calendar.time
            }
            
            // Check if it's a time format (HH:mm)
            val timeMatch = Regex("""(\d{1,2}):(\d{2})\s*(ص|م|AM|PM)?""").find(match.value)
            if (timeMatch != null) {
                var hours = timeMatch.groupValues[1].toInt()
                val minutes = timeMatch.groupValues[2].toInt()
                val amPm = timeMatch.groupValues.getOrNull(3)
                
                // Handle AM/PM
                when (amPm) {
                    "م", "PM" -> if (hours < 12) hours += 12
                    "ص", "AM" -> if (hours == 12) hours = 0
                }
                
                calendar.set(Calendar.HOUR_OF_DAY, hours)
                calendar.set(Calendar.MINUTE, minutes)
                calendar.set(Calendar.SECOND, 0)
                
                // If time is in the past, assume it's tomorrow
                if (calendar.time.before(Date())) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                
                return calendar.time
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ETA", e)
            null
        }
    }
    
    private fun shouldAutoPaste(packageName: String, event: AccessibilityEvent): Boolean {
        if (pendingPasteText == null || targetChatPackage != packageName) return false
        
        val className = event.className?.toString() ?: return false
        val appConfig = DeliveryApps.getByPackageName(packageName)
        
        // Check if we're in the chat activity
        return appConfig?.chatActivityClass?.let { className.contains("chat", ignoreCase = true) } ?: false
    }
    
    private fun autoPasteComplaint() {
        val text = pendingPasteText ?: return
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // Find text input field
            val inputNode = findEditText(rootNode)
            if (inputNode != null) {
                // Set the text
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                
                Log.d(TAG, "Auto-pasted complaint text")
                
                // Clear pending paste
                pendingPasteText = null
                targetChatPackage = null
                
                // Also copy to clipboard as backup
                copyToClipboard(text)
                
                inputNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error auto-pasting", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString()?.contains("EditText") == true && node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child)
            child.recycle()
            if (result != null) return result
        }
        
        return null
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Sanad Complaint", text)
        clipboard.setPrimaryClip(clip)
    }
    
    fun setPendingPaste(text: String, targetPackage: String) {
        pendingPasteText = text
        targetChatPackage = targetPackage
        Log.d(TAG, "Set pending paste for $targetPackage")
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceRunning = false
        Log.d(TAG, "Accessibility service destroyed")
    }
}
