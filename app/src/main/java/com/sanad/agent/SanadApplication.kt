package com.sanad.agent

import android.app.Application
import android.util.Log

class SanadApplication : Application() {
    
    companion object {
        private const val TAG = "SanadApp"
        lateinit var instance: SanadApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Sanad Application initialized")
    }
}
