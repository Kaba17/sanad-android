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
            
            btnViewStats.setOnClickListener {
                loadStats()
            }
            
            // Test Mode Buttons
            btnTestDelayed.setOnClickListener {
                createTestOrder("delayed")
            }
            
            btnTestOnTime.setOnClickListener {
                createTestOrder("on_time")
            }
            
            btnTestSoon.setOnClickListener {
                createTestOrder("soon")
            }
            
            btnClearTests.setOnClickListener {
                clearTestOrders()
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
                statusIcon.setImageResource(android.R.drawable.presence_online)
                statusText.text = "سند يراقب طلباتك"
                statusDescription.text = "سيتم إبلاغك تلقائياً عند تأخر أي طلب"
                btnEnableAccessibility.text = "الخدمة مفعّلة ✓"
                btnEnableAccessibility.isEnabled = false
            } else {
                statusIcon.setImageResource(android.R.drawable.presence_offline)
                statusText.text = "سند يحتاج إذنك"
                statusDescription.text = "اضغط الزر أدناه لتفعيل خدمة المراقبة"
                btnEnableAccessibility.text = "تفعيل الخدمة"
                btnEnableAccessibility.isEnabled = true
            }
            
            val activeOrders = orderManager.getActiveOrders()
            val delayedOrders = orderManager.getDelayedOrders()
            
            activeOrdersCount.text = "${activeOrders.size}"
            delayedOrdersCount.text = "${delayedOrders.size}"
        }
    }
    
    private fun loadOrders() {
        binding.ordersProgress.visibility = View.VISIBLE
        binding.emptyOrdersText.visibility = View.GONE
        
        lifecycleScope.launch {
            val result = apiClient.getOrders()
            result.onSuccess { orders ->
                runOnUiThread {
                    binding.ordersProgress.visibility = View.GONE
                    if (orders.isEmpty()) {
                        binding.emptyOrdersText.visibility = View.VISIBLE
                        binding.ordersRecyclerView.visibility = View.GONE
                    } else {
                        binding.emptyOrdersText.visibility = View.GONE
                        binding.ordersRecyclerView.visibility = View.VISIBLE
                        ordersAdapter.submitList(orders)
                    }
                    
                    // Update counts
                    val activeCount = orders.count { it.compensationStatus != "success" }
                    val delayedCount = orders.count { it.isDelayed == true }
                    binding.activeOrdersCount.text = "$activeCount"
                    binding.delayedOrdersCount.text = "$delayedCount"
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    binding.ordersProgress.visibility = View.GONE
                    binding.emptyOrdersText.visibility = View.VISIBLE
                    binding.emptyOrdersText.text = "تعذر تحميل الطلبات"
                    Toast.makeText(this@MainActivity, "خطأ في الاتصال بالخادم", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun createTestOrder(scenario: String) {
        val scenarioName = when (scenario) {
            "delayed" -> "متأخر"
            "on_time" -> "في الوقت"
            "soon" -> "قريب من الموعد"
            else -> scenario
        }
        
        Toast.makeText(this, "جاري إنشاء طلب $scenarioName...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            val result = apiClient.createTestOrder(scenario)
            result.onSuccess { response ->
                runOnUiThread {
                    val message = if (scenario == "delayed") {
                        "تم إنشاء طلب متأخر - تم توليد شكوى تلقائياً!"
                    } else {
                        "تم إنشاء طلب ${scenarioName}"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    loadOrders()
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "فشل إنشاء الطلب التجريبي", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun clearTestOrders() {
        lifecycleScope.launch {
            val result = apiClient.clearTestOrders()
            result.onSuccess { response ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "تم حذف ${response.deleted} طلب تجريبي", Toast.LENGTH_SHORT).show()
                    loadOrders()
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "فشل حذف الطلبات التجريبية", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showOrderDetails(order: Order) {
        val statusArabic = when (order.compensationStatus) {
            "monitoring" -> "يراقب"
            "intervening" -> "جاري التدخل"
            "claim_ready" -> "جاهز للإرسال"
            "awaiting_reply" -> "في انتظار الرد"
            "escalated" -> "تم التصعيد"
            "success" -> "تم التعويض"
            else -> order.compensationStatus ?: "غير معروف"
        }
        
        val message = buildString {
            append("التطبيق: ${order.appName}\n")
            append("رقم الطلب: ${order.orderId}\n")
            append("الحالة: $statusArabic\n")
            if (order.isDelayed == true) {
                append("متأخر: نعم\n")
            }
            if (!order.complaintText.isNullOrEmpty()) {
                append("\n--- نص الشكوى ---\n")
                append(order.complaintText)
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("تفاصيل الطلب")
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
            .apply {
                if (!order.complaintText.isNullOrEmpty()) {
                    setNeutralButton("نسخ الشكوى") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("complaint", order.complaintText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@MainActivity, "تم نسخ الشكوى", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
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
            .setTitle("تفعيل خدمة سند")
            .setMessage("""
                لكي يتمكن سند من مراقبة طلباتك وقراءة معلومات التوصيل، يحتاج إلى إذن "خدمات الوصول".
                
                هذا الإذن يسمح لسند بـ:
                • قراءة شاشة تطبيقات التوصيل
                • اكتشاف تأخر الطلبات
                • لصق نص الشكوى تلقائياً
                
                سند لا يجمع أي بيانات شخصية ولا يرسلها لأي جهة.
            """.trimIndent())
            .setPositiveButton("تفعيل") { _, _ ->
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
            .getString("server_url", "https://YOUR-REPLIT-URL.replit.app") ?: ""
        
        val editText = android.widget.EditText(this).apply {
            setText(currentUrl)
            hint = "https://your-server.replit.app"
        }
        
        AlertDialog.Builder(this)
            .setTitle("رابط الخادم")
            .setMessage("أدخل رابط خادم سند:")
            .setView(editText)
            .setPositiveButton("حفظ") { _, _ ->
                val newUrl = editText.text.toString().trim()
                if (newUrl.isNotEmpty()) {
                    apiClient.updateServerUrl(newUrl)
                    Toast.makeText(this, "تم حفظ الرابط", Toast.LENGTH_SHORT).show()
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
                    binding.apply {
                        statsCard.visibility = View.VISIBLE
                        totalRecovered.text = "${stats.totalRecovered} ر.س"
                        totalClaims.text = "${stats.totalClaims}"
                        escalatedWins.text = "${stats.escalatedWins}"
                    }
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    binding.statsCard.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "تعذر تحميل الإحصائيات", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
