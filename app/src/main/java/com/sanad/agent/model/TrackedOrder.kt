package com.sanad.agent.model

import java.util.Date

data class TrackedOrder(
    val orderId: String,
    val appName: String,
    val appPackage: String,
    val restaurantName: String? = null,
    val orderTotal: Int? = null,
    val eta: Date,
    val firstSeenAt: Date = Date(),
    var lastSeenAt: Date = Date(),
    var currentStatus: String = "monitoring",
    var isDelayed: Boolean = false,
    var delayMinutes: Int = 0,
    var serverOrderId: Int? = null,
    var complaintText: String? = null,
    var compensationStatus: String = CompensationStatus.MONITORING.value,
    var userNotified: Boolean = false,
    var userApproved: Boolean = false,
    var claimSent: Boolean = false
) {
    fun checkDelay(): Boolean {
        val now = Date()
        if (now.after(eta)) {
            val diffMs = now.time - eta.time
            delayMinutes = (diffMs / 60000).toInt()
            isDelayed = delayMinutes >= 15 // 15 minute threshold
            return isDelayed
        }
        return false
    }
    
    fun getDelayDescription(): String {
        return when {
            delayMinutes >= 60 -> "${delayMinutes / 60} ساعة و ${delayMinutes % 60} دقيقة"
            else -> "$delayMinutes دقيقة"
        }
    }
}
