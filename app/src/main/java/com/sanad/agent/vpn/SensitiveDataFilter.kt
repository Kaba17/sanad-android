package com.sanad.agent.vpn

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive

class SensitiveDataFilter {
    
    companion object {
        private const val TAG = "SensitiveDataFilter"
        
        private val SENSITIVE_KEYS = setOf(
            "password", "passwd", "pwd", "secret",
            "token", "access_token", "refresh_token", "auth_token", "api_key",
            "credit_card", "creditCard", "card_number", "cardNumber",
            "cvv", "cvc", "cvv2", "security_code", "securityCode",
            "expiry", "expiry_date", "expiryDate", "exp_month", "exp_year",
            "pin", "otp", "verification_code", "verificationCode",
            "ssn", "social_security", "national_id", "nationalId",
            "bank_account", "bankAccount", "account_number", "accountNumber",
            "iban", "swift", "routing_number", "routingNumber",
            "private_key", "privateKey", "secret_key", "secretKey",
            "session", "session_id", "sessionId", "cookie",
            "authorization", "bearer", "jwt",
            "billing_address", "billingAddress",
            "payment_method", "paymentMethod", "payment_info", "paymentInfo"
        )
        
        private val SENSITIVE_PATTERNS = listOf(
            Regex("""^\d{13,19}$"""),
            Regex("""^\d{3,4}$"""),
            Regex("""^[A-Za-z0-9-_]{20,}$"""),
            Regex("""^eyJ[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+$"""),
            Regex("""^Bearer\s+.+$""", RegexOption.IGNORE_CASE)
        )
        
        private const val REDACTED = "[REDACTED]"
    }
    
    fun sanitize(data: JsonObject): JsonObject {
        return sanitizeObject(data.deepCopy())
    }
    
    private fun sanitizeObject(obj: JsonObject): JsonObject {
        val keysToProcess = obj.keySet().toList()
        
        for (key in keysToProcess) {
            val lowerKey = key.lowercase()
            
            if (SENSITIVE_KEYS.any { lowerKey.contains(it) }) {
                obj.addProperty(key, REDACTED)
                Log.d(TAG, "Redacted sensitive key: $key")
                continue
            }
            
            val value = obj.get(key)
            when {
                value == null || value.isJsonNull -> continue
                value.isJsonObject -> obj.add(key, sanitizeObject(value.asJsonObject))
                value.isJsonArray -> obj.add(key, sanitizeArray(value.asJsonArray))
                value.isJsonPrimitive -> {
                    val sanitized = sanitizePrimitive(value.asJsonPrimitive)
                    if (sanitized != value) {
                        obj.add(key, sanitized)
                        Log.d(TAG, "Redacted sensitive value in key: $key")
                    }
                }
            }
        }
        
        return obj
    }
    
    private fun sanitizeArray(arr: JsonArray): JsonArray {
        val result = JsonArray()
        
        for (element in arr) {
            when {
                element == null || element.isJsonNull -> result.add(element)
                element.isJsonObject -> result.add(sanitizeObject(element.asJsonObject))
                element.isJsonArray -> result.add(sanitizeArray(element.asJsonArray))
                element.isJsonPrimitive -> result.add(sanitizePrimitive(element.asJsonPrimitive))
            }
        }
        
        return result
    }
    
    private fun sanitizePrimitive(primitive: JsonPrimitive): JsonElement {
        if (!primitive.isString) return primitive
        
        val value = primitive.asString
        
        if (SENSITIVE_PATTERNS.any { it.matches(value) }) {
            return JsonPrimitive(REDACTED)
        }
        
        if (looksLikeCreditCard(value)) {
            return JsonPrimitive(REDACTED)
        }
        
        return primitive
    }
    
    private fun looksLikeCreditCard(value: String): Boolean {
        val digits = value.replace(Regex("""\D"""), "")
        if (digits.length !in 13..19) return false
        
        return luhnCheck(digits)
    }
    
    private fun luhnCheck(number: String): Boolean {
        var sum = 0
        var alternate = false
        
        for (i in number.length - 1 downTo 0) {
            var digit = number[i].digitToInt()
            
            if (alternate) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            
            sum += digit
            alternate = !alternate
        }
        
        return sum % 10 == 0
    }
}
