package com.sanad.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sanad.agent.api.SanadApiClient
import com.sanad.agent.databinding.ActivityMainBinding
import com.sanad.agent.service.SanadAccessibilityService
import com.sanad.agent.service.SanadMonitorService
import com.sanad.agent.util.OrderManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var orderManager: OrderManager
    private lateinit var apiClient: SanadApiClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        orderManager = OrderManager.getInstance(this)
        apiClient = SanadApiClient.getInstance(this)
        
        setupUI()
        checkAccessibilityPermission()
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
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
            
            // Show tracked orders count
            val activeOrders = orderManager.getActiveOrders()
            val delayedOrders = orderManager.getDelayedOrders()
            
            activeOrdersCount.text = "${activeOrders.size}"
            delayedOrdersCount.text = "${delayedOrders.size}"
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
                        statsCard.visibility = android.view.View.VISIBLE
                        totalRecovered.text = "${stats.totalRecovered} ر.س"
                        totalClaims.text = "${stats.totalClaims}"
                        escalatedWins.text = "${stats.escalatedWins}"
                    }
                }
            }
            result.onFailure { error ->
                runOnUiThread {
                    binding.statsCard.visibility = android.view.View.GONE
                    // Show error toast
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "تعذر تحميل الإحصائيات",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
