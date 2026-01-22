package com.sanad.agent.ssl

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sanad.agent.model.Order

class DeliveryAppParser {
    
    companion object {
        private const val TAG = "DeliveryAppParser"
    }
    
    data class ParsedOrder(
        val orderId: String,
        val restaurantName: String,
        val orderStatus: String,
        val eta: String?,
        val deliveryApp: String,
        val totalAmount: Double?,
        val rawJson: String
    )
    
    fun parseInterceptedData(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        return try {
            when {
                data.hostname.contains("hungerstation") -> parseHungerstation(data)
                data.hostname.contains("jahez") -> parseJahez(data)
                data.hostname.contains("toyou") -> parseToYou(data)
                data.hostname.contains("mrsool") -> parseMrsool(data)
                data.hostname.contains("careem") -> parseCareem(data)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response from ${data.hostname}", e)
            null
        }
    }
    
    private fun parseHungerstation(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        if (!isOrderRelatedPath(data.path)) return null
        
        val json = parseJson(data.responseBody) ?: return null
        
        val orderId = extractField(json, listOf("order_id", "orderId", "id", "order.id"))
        val restaurantName = extractField(json, listOf("restaurant_name", "restaurantName", "restaurant.name", "vendor_name", "vendorName"))
        val status = extractField(json, listOf("status", "order_status", "orderStatus", "state"))
        val eta = extractField(json, listOf("eta", "estimated_delivery_time", "estimatedDeliveryTime", "delivery_time"))
        val amount = extractNumber(json, listOf("total", "total_amount", "totalAmount", "grand_total"))
        
        if (orderId == null && restaurantName == null) return null
        
        return ParsedOrder(
            orderId = orderId ?: "unknown",
            restaurantName = restaurantName ?: "مطعم غير معروف",
            orderStatus = status ?: "unknown",
            eta = eta,
            deliveryApp = "هنقرستيشن",
            totalAmount = amount,
            rawJson = data.responseBody
        )
    }
    
    private fun parseJahez(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        if (!isOrderRelatedPath(data.path)) return null
        
        val json = parseJson(data.responseBody) ?: return null
        
        val orderId = extractField(json, listOf("orderId", "order_id", "id", "data.orderId"))
        val restaurantName = extractField(json, listOf("restaurantName", "restaurant_name", "storeName", "store_name"))
        val status = extractField(json, listOf("status", "orderStatus", "order_status"))
        val eta = extractField(json, listOf("eta", "estimatedTime", "estimated_time", "deliveryTime"))
        val amount = extractNumber(json, listOf("total", "totalPrice", "total_price", "amount"))
        
        if (orderId == null && restaurantName == null) return null
        
        return ParsedOrder(
            orderId = orderId ?: "unknown",
            restaurantName = restaurantName ?: "مطعم غير معروف",
            orderStatus = status ?: "unknown",
            eta = eta,
            deliveryApp = "جاهز",
            totalAmount = amount,
            rawJson = data.responseBody
        )
    }
    
    private fun parseToYou(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        if (!isOrderRelatedPath(data.path)) return null
        
        val json = parseJson(data.responseBody) ?: return null
        
        val orderId = extractField(json, listOf("order_id", "orderId", "id"))
        val restaurantName = extractField(json, listOf("store_name", "storeName", "restaurant_name", "vendor"))
        val status = extractField(json, listOf("status", "order_status", "state"))
        val eta = extractField(json, listOf("eta", "delivery_eta", "estimated_arrival"))
        val amount = extractNumber(json, listOf("total", "total_amount", "price"))
        
        if (orderId == null && restaurantName == null) return null
        
        return ParsedOrder(
            orderId = orderId ?: "unknown",
            restaurantName = restaurantName ?: "مطعم غير معروف",
            orderStatus = status ?: "unknown",
            eta = eta,
            deliveryApp = "تويو",
            totalAmount = amount,
            rawJson = data.responseBody
        )
    }
    
    private fun parseMrsool(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        if (!isOrderRelatedPath(data.path)) return null
        
        val json = parseJson(data.responseBody) ?: return null
        
        val orderId = extractField(json, listOf("order_id", "orderId", "id", "request_id"))
        val restaurantName = extractField(json, listOf("store_name", "storeName", "merchant_name", "vendor_name"))
        val status = extractField(json, listOf("status", "order_status", "state", "delivery_status"))
        val eta = extractField(json, listOf("eta", "estimated_time", "arrival_time"))
        val amount = extractNumber(json, listOf("total", "total_amount", "price", "cost"))
        
        if (orderId == null && restaurantName == null) return null
        
        return ParsedOrder(
            orderId = orderId ?: "unknown",
            restaurantName = restaurantName ?: "مطعم غير معروف",
            orderStatus = status ?: "unknown",
            eta = eta,
            deliveryApp = "مرسول",
            totalAmount = amount,
            rawJson = data.responseBody
        )
    }
    
    private fun parseCareem(data: SanadHttpProxy.InterceptedData): ParsedOrder? {
        if (!isOrderRelatedPath(data.path)) return null
        
        val json = parseJson(data.responseBody) ?: return null
        
        val orderId = extractField(json, listOf("order_id", "orderId", "id", "booking_id"))
        val restaurantName = extractField(json, listOf("vendor_name", "vendorName", "restaurant_name", "merchant"))
        val status = extractField(json, listOf("status", "order_status", "state", "booking_status"))
        val eta = extractField(json, listOf("eta", "estimated_arrival", "arrival_eta", "delivery_eta"))
        val amount = extractNumber(json, listOf("total", "total_fare", "amount", "price"))
        
        if (orderId == null && restaurantName == null) return null
        
        return ParsedOrder(
            orderId = orderId ?: "unknown",
            restaurantName = restaurantName ?: "مطعم غير معروف",
            orderStatus = status ?: "unknown",
            eta = eta,
            deliveryApp = "كريم",
            totalAmount = amount,
            rawJson = data.responseBody
        )
    }
    
    private fun isOrderRelatedPath(path: String): Boolean {
        val orderPaths = listOf(
            "/order", "/orders", "/track", "/tracking",
            "/delivery", "/status", "/details", "/my-orders",
            "/active", "/current", "/history"
        )
        return orderPaths.any { path.lowercase().contains(it) }
    }
    
    private fun parseJson(body: String): JsonObject? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                JsonParser.parseString(trimmed).asJsonObject
            } else if (trimmed.startsWith("[")) {
                val array = JsonParser.parseString(trimmed).asJsonArray
                if (array.size() > 0 && array[0].isJsonObject) {
                    array[0].asJsonObject
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractField(json: JsonObject, paths: List<String>): String? {
        for (path in paths) {
            val value = getNestedValue(json, path)
            if (value != null) return value
        }
        return null
    }
    
    private fun extractNumber(json: JsonObject, paths: List<String>): Double? {
        for (path in paths) {
            val value = getNestedNumber(json, path)
            if (value != null) return value
        }
        return null
    }
    
    private fun getNestedValue(json: JsonObject, path: String): String? {
        return try {
            val parts = path.split(".")
            var current: Any = json
            
            for (part in parts) {
                if (current is JsonObject) {
                    if (current.has(part)) {
                        val element = current.get(part)
                        current = if (element.isJsonObject) {
                            element.asJsonObject
                        } else if (element.isJsonPrimitive) {
                            return element.asString
                        } else {
                            return null
                        }
                    } else {
                        return null
                    }
                } else {
                    return null
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getNestedNumber(json: JsonObject, path: String): Double? {
        return try {
            val parts = path.split(".")
            var current: Any = json
            
            for (part in parts) {
                if (current is JsonObject) {
                    if (current.has(part)) {
                        val element = current.get(part)
                        current = if (element.isJsonObject) {
                            element.asJsonObject
                        } else if (element.isJsonPrimitive) {
                            return element.asDouble
                        } else {
                            return null
                        }
                    } else {
                        return null
                    }
                } else {
                    return null
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
