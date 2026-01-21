package com.sanad.agent.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sanad.agent.model.TrackedOrder

class OrderManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "OrderManager"
        private const val PREFS_NAME = "sanad_orders"
        private const val KEY_TRACKED_ORDERS = "tracked_orders"
        
        @Volatile
        private var INSTANCE: OrderManager? = null
        
        fun getInstance(context: Context): OrderManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OrderManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val orders = mutableMapOf<String, TrackedOrder>()
    
    init {
        loadOrders()
    }
    
    private fun loadOrders() {
        val json = prefs.getString(KEY_TRACKED_ORDERS, null) ?: return
        try {
            val type = object : TypeToken<Map<String, TrackedOrder>>() {}.type
            val loaded: Map<String, TrackedOrder> = gson.fromJson(json, type)
            orders.putAll(loaded)
            Log.d(TAG, "Loaded ${orders.size} tracked orders")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading orders", e)
        }
    }
    
    private fun saveOrders() {
        try {
            val json = gson.toJson(orders)
            prefs.edit().putString(KEY_TRACKED_ORDERS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving orders", e)
        }
    }
    
    fun trackOrder(order: TrackedOrder) {
        val key = "${order.appName}_${order.orderId}"
        
        // Check if we're already tracking this order
        val existing = orders[key]
        if (existing != null) {
            // Update existing order
            existing.lastSeenAt = order.lastSeenAt
            existing.currentStatus = order.currentStatus
            if (order.eta.after(existing.eta)) {
                existing.eta // Keep original ETA for delay calculation
            }
        } else {
            // New order
            orders[key] = order
            Log.d(TAG, "Started tracking new order: $key")
        }
        
        saveOrders()
    }
    
    fun updateOrder(order: TrackedOrder) {
        val key = "${order.appName}_${order.orderId}"
        orders[key] = order
        saveOrders()
    }
    
    fun getOrderById(orderId: String): TrackedOrder? {
        return orders.values.find { it.orderId == orderId }
    }
    
    fun getOrderByServerId(serverId: Int): TrackedOrder? {
        return orders.values.find { it.serverOrderId == serverId }
    }
    
    fun getActiveOrders(): List<TrackedOrder> {
        return orders.values.filter { 
            it.compensationStatus != "success" && 
            it.compensationStatus != "dismissed"
        }
    }
    
    fun getDelayedOrders(): List<TrackedOrder> {
        return orders.values.filter { it.isDelayed }
    }
    
    fun getAllOrders(): List<TrackedOrder> {
        return orders.values.toList()
    }
    
    fun removeOrder(orderId: String) {
        val keysToRemove = orders.keys.filter { it.endsWith("_$orderId") }
        keysToRemove.forEach { orders.remove(it) }
        saveOrders()
    }
    
    fun clearOldOrders(maxAgeHours: Int = 48) {
        val cutoff = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000)
        val keysToRemove = orders.entries
            .filter { it.value.firstSeenAt.time < cutoff }
            .map { it.key }
        
        keysToRemove.forEach { orders.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            saveOrders()
            Log.d(TAG, "Cleaned up ${keysToRemove.size} old orders")
        }
    }
}
