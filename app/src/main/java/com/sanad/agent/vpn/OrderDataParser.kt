package com.sanad.agent.vpn

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

class OrderDataParser {
    
    companion object {
        private const val TAG = "OrderDataParser"
        
        private val ORDER_ID_KEYS = listOf(
            "order_id", "orderId", "id", "order_number", "orderNumber",
            "reference", "ref", "tracking_id", "trackingId"
        )
        
        private val ETA_KEYS = listOf(
            "eta", "estimated_time", "estimatedTime", "delivery_time",
            "deliveryTime", "expected_at", "expectedAt", "arrival_time",
            "arrivalTime", "estimated_delivery", "estimatedDelivery"
        )
        
        private val STATUS_KEYS = listOf(
            "status", "order_status", "orderStatus", "state",
            "delivery_status", "deliveryStatus", "current_status"
        )
        
        private val RESTAURANT_KEYS = listOf(
            "restaurant", "vendor", "store", "merchant", "shop",
            "restaurant_name", "restaurantName", "vendor_name", "vendorName"
        )
        
        private val ITEMS_KEYS = listOf(
            "items", "order_items", "orderItems", "products", "cart_items"
        )
        
        private val TOTAL_KEYS = listOf(
            "total", "total_amount", "totalAmount", "grand_total",
            "grandTotal", "amount", "price", "order_total"
        )
    }
    
    fun extractOrderData(json: JsonElement, appName: String): JsonObject? {
        return try {
            when {
                json.isJsonObject -> parseObject(json.asJsonObject, appName)
                json.isJsonArray -> parseArray(json.asJsonArray, appName)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting order data", e)
            null
        }
    }
    
    private fun parseObject(obj: JsonObject, appName: String): JsonObject? {
        val orderId = findValue(obj, ORDER_ID_KEYS)
        if (orderId == null) {
            val nestedOrder = findNestedObject(obj, listOf("order", "data", "result", "payload"))
            if (nestedOrder != null) {
                return parseObject(nestedOrder, appName)
            }
            return null
        }
        
        val result = JsonObject()
        result.addProperty("appName", appName)
        result.addProperty("orderId", orderId)
        
        findValue(obj, ETA_KEYS)?.let { result.addProperty("eta", it) }
        findValue(obj, STATUS_KEYS)?.let { result.addProperty("status", it) }
        findValue(obj, TOTAL_KEYS)?.let { result.addProperty("total", it) }
        
        findNestedValue(obj, RESTAURANT_KEYS)?.let { restaurant ->
            if (restaurant is JsonObject) {
                val name = restaurant.get("name")?.asString 
                    ?: restaurant.get("title")?.asString
                result.addProperty("restaurantName", name)
            } else {
                result.addProperty("restaurantName", restaurant.toString())
            }
        }
        
        findNestedArray(obj, ITEMS_KEYS)?.let { items ->
            val itemNames = items.mapNotNull { item ->
                if (item.isJsonObject) {
                    item.asJsonObject.get("name")?.asString
                        ?: item.asJsonObject.get("title")?.asString
                        ?: item.asJsonObject.get("product_name")?.asString
                } else null
            }
            result.addProperty("items", itemNames.joinToString(", "))
        }
        
        findDriverInfo(obj)?.let { driver ->
            result.add("driver", driver)
        }
        
        findLocation(obj)?.let { location ->
            result.add("deliveryLocation", location)
        }
        
        Log.d(TAG, "Extracted order: $orderId from $appName")
        return result
    }
    
    private fun parseArray(arr: JsonArray, appName: String): JsonObject? {
        for (element in arr) {
            if (element.isJsonObject) {
                val result = parseObject(element.asJsonObject, appName)
                if (result != null) return result
            }
        }
        return null
    }
    
    private fun findValue(obj: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val value = obj.get(key)
            if (value != null && !value.isJsonNull) {
                return when {
                    value.isJsonPrimitive -> value.asString
                    else -> value.toString()
                }
            }
        }
        return null
    }
    
    private fun findNestedObject(obj: JsonObject, keys: List<String>): JsonObject? {
        for (key in keys) {
            val value = obj.get(key)
            if (value != null && value.isJsonObject) {
                return value.asJsonObject
            }
        }
        return null
    }
    
    private fun findNestedValue(obj: JsonObject, keys: List<String>): JsonElement? {
        for (key in keys) {
            val value = obj.get(key)
            if (value != null && !value.isJsonNull) {
                return value
            }
        }
        return null
    }
    
    private fun findNestedArray(obj: JsonObject, keys: List<String>): JsonArray? {
        for (key in keys) {
            val value = obj.get(key)
            if (value != null && value.isJsonArray) {
                return value.asJsonArray
            }
        }
        return null
    }
    
    private fun findDriverInfo(obj: JsonObject): JsonObject? {
        val driverKeys = listOf("driver", "captain", "courier", "delivery_person")
        for (key in driverKeys) {
            val driver = obj.get(key)
            if (driver != null && driver.isJsonObject) {
                val driverObj = driver.asJsonObject
                val result = JsonObject()
                driverObj.get("name")?.let { result.add("name", it) }
                driverObj.get("phone")?.let { result.add("phone", it) }
                driverObj.get("rating")?.let { result.add("rating", it) }
                if (result.size() > 0) return result
            }
        }
        return null
    }
    
    private fun findLocation(obj: JsonObject): JsonObject? {
        val locationKeys = listOf("location", "address", "delivery_address", "destination")
        for (key in locationKeys) {
            val loc = obj.get(key)
            if (loc != null && loc.isJsonObject) {
                val locObj = loc.asJsonObject
                val result = JsonObject()
                locObj.get("lat")?.let { result.add("lat", it) }
                locObj.get("lng")?.let { result.add("lng", it) }
                locObj.get("latitude")?.let { result.add("lat", it) }
                locObj.get("longitude")?.let { result.add("lng", it) }
                if (result.size() > 0) return result
            }
        }
        return null
    }
}
