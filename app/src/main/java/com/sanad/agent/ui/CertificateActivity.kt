package com.sanad.agent.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.sanad.agent.databinding.ActivityCertificateBinding
import com.sanad.agent.ssl.SanadCertificateManager
import com.sanad.agent.ssl.SanadHttpProxy
import kotlinx.coroutines.launch

class CertificateActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCertificateBinding
    private lateinit var certificateManager: SanadCertificateManager
    private var httpProxy: SanadHttpProxy? = null
    private var isProxyRunning = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        certificateManager = SanadCertificateManager(this)
        certificateManager.initialize()
        
        setupUI()
        updateStatus()
    }
    
    private fun setupUI() {
        binding.btnExportCert.setOnClickListener {
            exportCertificate()
        }
        
        binding.btnInstallCert.setOnClickListener {
            installCertificate()
        }
        
        binding.btnOpenSettings.setOnClickListener {
            openSecuritySettings()
        }
        
        binding.btnStartProxy.setOnClickListener {
            toggleProxy()
        }
        
        binding.btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun toggleProxy() {
        if (isProxyRunning) {
            stopProxy()
        } else {
            startProxy()
        }
    }
    
    private fun startProxy() {
        lifecycleScope.launch {
            try {
                val serverUrl = getSharedPreferences("sanad_prefs", MODE_PRIVATE)
                    .getString("server_url", "") ?: ""
                
                if (serverUrl.isEmpty()) {
                    Toast.makeText(
                        this@CertificateActivity,
                        "يجب ضبط رابط الخادم أولاً من الإعدادات",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                
                httpProxy = SanadHttpProxy(certificateManager, serverUrl)
                httpProxy?.start()
                
                isProxyRunning = true
                updateProxyUI()
                
                Toast.makeText(
                    this@CertificateActivity,
                    "تم تشغيل Proxy على المنفذ 8888",
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@CertificateActivity,
                    "فشل تشغيل Proxy: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun stopProxy() {
        httpProxy?.stop()
        httpProxy = null
        isProxyRunning = false
        updateProxyUI()
        
        Toast.makeText(this, "تم إيقاف Proxy", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateProxyUI() {
        if (isProxyRunning) {
            binding.btnStartProxy.text = "إيقاف Proxy"
            binding.proxyStatusCard.visibility = View.VISIBLE
        } else {
            binding.btnStartProxy.text = "تشغيل Proxy"
            binding.proxyStatusCard.visibility = View.GONE
        }
    }
    
    override fun onDestroy() {
        httpProxy?.stop()
        super.onDestroy()
    }
    
    private fun updateStatus() {
        val isExported = certificateManager.isCACertificateExported()
        
        if (isExported) {
            binding.tvStatus.text = "الشهادة جاهزة للتثبيت"
            binding.tvStatusIcon.text = "✓"
            binding.btnInstallCert.isEnabled = true
        } else {
            binding.tvStatus.text = "يجب تصدير الشهادة أولاً"
            binding.tvStatusIcon.text = "○"
            binding.btnInstallCert.isEnabled = false
        }
    }
    
    private fun exportCertificate() {
        val certFile = certificateManager.exportCACertificate()
        
        if (certFile != null && certFile.exists()) {
            Toast.makeText(this, "تم تصدير الشهادة بنجاح", Toast.LENGTH_SHORT).show()
            updateStatus()
        } else {
            Toast.makeText(this, "فشل في تصدير الشهادة", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun installCertificate() {
        val certFile = certificateManager.getCACertificateFile()
        
        if (!certFile.exists()) {
            Toast.makeText(this, "يجب تصدير الشهادة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                certFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/x-x509-ca-cert")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                openSecuritySettings()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "فشل في فتح الشهادة: ${e.message}", Toast.LENGTH_LONG).show()
            openSecuritySettings()
        }
    }
    
    private fun openSecuritySettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }
            startActivity(intent)
            
            Toast.makeText(
                this,
                "ابحث عن 'تثبيت شهادة' أو 'Install certificate'",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل في فتح الإعدادات", Toast.LENGTH_SHORT).show()
        }
    }
}
