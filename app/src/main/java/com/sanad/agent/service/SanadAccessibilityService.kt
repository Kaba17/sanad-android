package com.sanad.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.model.DeliveryApps
import kotlinx.coroutines.*

class SanadAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "SanadAccessibility"
        var instance: SanadAccessibilityService? = null
            private set
        
        var isServiceRunning = false
            private set
        
        private const val ANALYSIS_DEBOUNCE_MS = 3000L
        private const val MIN_TEXT_LENGTH = 50
        private const val NOTIFICATION_COOLDOWN_MS = 30000L
    }
    
    private lateinit var apiClient: SanadApiClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAnalysisTime = 0L
    private var lastAnalyzedText = ""
    private var pendingPasteText: String? = null
    private var targetChatPackage: String? = null
    private var lastNotifiedApp: String? = null
    private var lastNotificationTime = 0L
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceRunning = true
        
        apiClient = SanadApiClient.getInstance(applicationContext)
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 500
            packageNames = DeliveryApps.getSupportedPackageNames().toTypedArray()
        }
        serviceInfo = info
        
        Log.d(TAG, "Sanad AI Accessibility Service connected - monitoring delivery apps")
        
        val serviceIntent = Intent(this, SanadMonitorService::class.java)
        startForegroundService(serviceIntent)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        val packageName = event.packageName?.toString() ?: return
        if (!DeliveryApps.getSupportedPackageNames().contains(packageName)) return
        
        showWatchingNotification(packageName)
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (shouldAutoPaste(packageName, event)) {
                    autoPasteComplaint()
                } else {
                    val rootNode = rootInActiveWindow ?: return
                    analyzeScreenWithAI(rootNode, packageName)
                    rootNode.recycle()
                }
            }
        }
    }
    
    private fun showWatchingNotification(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastNotifiedApp && now - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) {
            return
        }
        
        lastNotifiedApp = packageName
        lastNotificationTime = now
        
        val appConfig = DeliveryApps.getByPackageName(packageName)
        val appName = appConfig?.appNameArabic ?: "التطبيق"
        
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "سند معاك - نراقب طلبك من $appName",
                Toast.LENGTH_SHORT
            ).show()
        }
        
        Log.d(TAG, "Showed watching notification for $appName")
    }
    
    private fun analyzeScreenWithAI(rootNode: AccessibilityNodeInfo, packageName: String) {
        val screenText = extractAllText(rootNode)
        
        if (screenText.length < MIN_TEXT_LENGTH) {
            return
        }
        
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < ANALYSIS_DEBOUNCE_MS) {
            return
        }
        
        val textHash = screenText.hashCode().toString()
        if (textHash == lastAnalyzedText) {
            return
        }
        
        lastAnalysisTime = now
        lastAnalyzedText = textHash
        
        Log.d(TAG, "Sending screen text to AI for analysis (${screenText.length} chars)")
        
        serviceScope.launch {
            try {
                val result = apiClient.analyzeScreen(screenText, packageName)
                result.onSuccess { response ->
                    if (response.detected) {
                        Log.d(TAG, "AI detected order: ${response.extracted?.orderId} (${response.action})")
                        response.order?.let { order ->
                            if (order.compensationStatus == "claim_ready") {
                                Log.d(TAG, "Claim ready for order ${order.orderId}")
                            }
                        }
                    }
                }
                result.onFailure { error ->
                    Log.e(TAG, "AI analysis failed: ${error.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AI analysis", e)
            }
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
    
    private fun shouldAutoPaste(packageName: String, event: AccessibilityEvent): Boolean {
        if (pendingPasteText == null || targetChatPackage != packageName) return false
        
        val className = event.className?.toString() ?: return false
        val appConfig = DeliveryApps.getByPackageName(packageName)
        
        return appConfig?.chatActivityClass?.let { className.contains("chat", ignoreCase = true) } ?: false
    }
    
    private fun autoPasteComplaint() {
        val text = pendingPasteText ?: return
        val rootNode = rootInActiveWindow ?: return
        
        try {
            val inputNode = findEditText(rootNode)
            if (inputNode != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                
                Log.d(TAG, "Auto-pasted complaint text")
                
                pendingPasteText = null
                targetChatPackage = null
                
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
        serviceScope.cancel()
        Log.d(TAG, "Accessibility service destroyed")
    }
}
