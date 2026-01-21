package com.sanad.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanad.agent.R
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.databinding.ActivityMainBinding
import com.sanad.agent.model.Order
import com.sanad.agent.service.SanadAccessibilityService
import com.sanad.agent.util.OrderManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var orderManager: OrderManager
    private lateinit var apiClient: SanadApiClient
    private lateinit var ordersAdapter: OrdersAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        orderManager = OrderManager.getInstance(this)
        apiClient = SanadApiClient.getInstance(this)
        
        setupUI()
        setupOrdersList()
        checkAccessibilityPermission()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
        loadOrders()
    }
    
    private fun setupUI() {
        binding.apply {
            btnEnableAccessibility.setOnClickListener {
                openAccessibilitySettings()
            }
            
            btnSettings.setOnClickListener {
                showServerUrlDialog()
            }
            
            btnRefreshOrders.setOnClickListener {
                loadOrders()
            }
        }
    }
    
    private fun setupOrdersList() {
        ordersAdapter = OrdersAdapter { order ->
            showOrderDetails(order)
        }
        binding.ordersRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ordersAdapter
        }
    }
    
    private fun updateUI() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        
        binding.apply {
            if (isAccessibilityEnabled) {
                mainStatusCard.setCardBackgroundColor(getColor(R.color.primary))
                statusIcon.setImageResource(R.drawable.ic_check)
                statusTitle.text = "سند يحميك"
                statusSubtitle.text = "يراقب طلباتك ويتدخل عند التأخير"
                btnEnableAccessibility.visibility = View.GONE
            } else {
                mainStatusCard.setCardBackgroundColor(getColor(R.color.warning))
                statusIcon.setImageResource(R.drawable.ic_notification)
                statusTitle.text = "سند يحتاج تفعيل"
                statusSubtitle.text = "فعّل الخدمة لمراقبة طلباتك تلقائياً"
                btnEnableAccessibility.visibility = View.VISIBLE
            }
        }
    }
    
    private fun loadOrders() {
        binding.ordersProgress.visibility = View.VISIBLE
        binding.emptyStateLayout.visibility = View.GONE
        
        lifecycleScope.launch {
            val result = apiClient.getOrders()
            result.onSuccess { orders ->
                runOnUiThread {
                    binding.ordersProgress.visibility = View.GONE
                    
                    val activeOrders = orders.filter { it.compensationStatus != "success" }
                    val claimsReady = orders.filter { 
                        it.compensationStatus == "claim_ready" || 
                        !it.complaintText.isNullOrEmpty() 
                    }
                    
                    binding.activeOrdersCount.text = "${activeOrders.size}"
                    binding.claimsReadyCount.text = "${claimsReady.size}"
                    
                    if (orders.isEmpty()) {
                        binding.emptyStateLayout.visibility = View.VISIBLE
                        binding.ordersRecyclerView.visibility = View.GONE
                        binding.ordersHeader.visibility = View.GONE
                        binding.monitoringCard.visibility = View.GONE
                    } else {
                        binding.emptyStateLayout.visibility = View.GONE
                        binding.ordersRecyclerView.visibility = View.VISIBLE
                        binding.ordersHeader.visibility = View.VISIBLE
                        ordersAdapter.submitList(orders)
                        
                        // Show monitoring card if there are active orders
                        if (activeOrders.isNotEmpty()) {
                            binding.monitoringCard.visibility = View.VISIBLE
                            val orderWord = if (activeOrders.size == 1) "طلبك" else "طلباتك"
                            binding.monitoringMessage.text = 
                                "سند يراقب $orderWord الآن. إذا تأخر أي طلب، سيجهز لك شكوى احترافية تلقائياً للحصول على تعويضك."
                        } else {
                            binding.monitoringCard.visibility = View.GONE
                        }
                    }
                    
                    // Load stats if there are successful claims
                    val successfulClaims = orders.count { it.compensationStatus == "success" }
                    if (successfulClaims > 0) {
                        loadStats()
                    }
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    binding.ordersProgress.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                }
            }
        }
    }
    
    private fun showOrderDetails(order: Order) {
        val statusInfo = getStatusInfo(order.compensationStatus ?: "monitoring")
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_Sanad_Dialog)
        
        val message = buildString {
            append("التطبيق: ${order.appName}\n")
            append("رقم الطلب: ${order.orderId}\n\n")
            append("الحالة: ${statusInfo.first}\n")
            append(statusInfo.second)
            
            if (order.isDelayed == true) {
                append("\n\nتم رصد تأخير في هذا الطلب")
            }
            
            if (!order.complaintText.isNullOrEmpty()) {
                append("\n\n-------------------\n")
                append("الشكوى الجاهزة:\n\n")
                append(order.complaintText)
            }
        }
        
        dialog.setTitle("تفاصيل الطلب")
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
        
        if (!order.complaintText.isNullOrEmpty()) {
            dialog.setNeutralButton("نسخ الشكوى") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("complaint", order.complaintText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "تم نسخ الشكوى", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
    }
    
    private fun getStatusInfo(status: String): Pair<String, String> {
        return when (status) {
            "monitoring" -> Pair(
                "يراقب",
                "سند يراقب هذا الطلب ويتتبع وقت التوصيل"
            )
            "intervening" -> Pair(
                "جاري التدخل",
                "تم رصد تأخير، سند يجهز الشكوى الآن..."
            )
            "claim_ready" -> Pair(
                "الشكوى جاهزة",
                "اضغط على نسخ الشكوى وأرسلها للتطبيق"
            )
            "awaiting_reply" -> Pair(
                "في انتظار الرد",
                "تم إرسال الشكوى، بانتظار رد التطبيق"
            )
            "escalated" -> Pair(
                "تم التصعيد",
                "تم تصعيد الشكوى لجهة حماية المستهلك"
            )
            "success" -> Pair(
                "تم التعويض",
                "مبروك! تم الحصول على التعويض بنجاح"
            )
            else -> Pair(status, "")
        }
    }
    
    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityExplanation()
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == SanadAccessibilityService::class.java.name
        }
    }
    
    private fun showAccessibilityExplanation() {
        AlertDialog.Builder(this)
            .setTitle("تفعيل سند")
            .setMessage("""
                لكي يتمكن سند من مراقبة طلباتك تلقائياً، يحتاج إلى إذن خاص.
                
                ماذا يفعل سند؟
                • يقرأ معلومات الطلب من تطبيقات التوصيل
                • يكتشف التأخير تلقائياً
                • يجهز لك شكوى احترافية
                
                سند لا يجمع بياناتك ولا يشاركها.
            """.trimIndent())
            .setPositiveButton("تفعيل الآن") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("لاحقاً", null)
            .show()
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
    
    private fun showServerUrlDialog() {
        val currentUrl = getSharedPreferences("sanad_prefs", MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        
        val editText = android.widget.EditText(this).apply {
            setText(currentUrl)
            hint = "https://your-server.replit.app"
            setPadding(48, 32, 48, 32)
        }
        
        AlertDialog.Builder(this)
            .setTitle("إعدادات الخادم")
            .setMessage("أدخل رابط خادم سند:")
            .setView(editText)
            .setPositiveButton("حفظ") { _, _ ->
                val newUrl = editText.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    apiClient.updateServerUrl(newUrl)
                    Toast.makeText(this, "تم حفظ الإعدادات", Toast.LENGTH_SHORT).show()
                    loadOrders()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    
    private fun loadStats() {
        lifecycleScope.launch {
            val result = apiClient.getStats()
            result.onSuccess { stats ->
                runOnUiThread {
                    if (stats.totalClaims > 0) {
                        binding.apply {
                            statsCard.visibility = View.VISIBLE
                            totalRecovered.text = "${stats.totalRecovered} ر.س"
                            totalClaims.text = "${stats.totalClaims}"
                        }
                    }
                }
            }
            result.onFailure {
                runOnUiThread {
                    binding.statsCard.visibility = View.GONE
                }
            }
        }
    }
}
