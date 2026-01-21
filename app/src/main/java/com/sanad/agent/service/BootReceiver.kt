package com.sanad.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SanadBoot"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device booted - starting Sanad monitor service")
            
            // Check if accessibility service is enabled
            if (SanadAccessibilityService.isServiceRunning) {
                // Start the foreground monitoring service
                val serviceIntent = Intent(context, SanadMonitorService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
