package com.sanad.agent.model

import com.google.gson.annotations.SerializedName
import java.util.Date

data class Order(
    val id: Int? = null,
    @SerializedName("appName") val appName: String,
    @SerializedName("orderId") val orderId: String,
    @SerializedName("currentStatus") val currentStatus: String,
    val eta: Date,
    @SerializedName("deliveredAt") val deliveredAt: Date? = null,
    @SerializedName("isDelayed") val isDelayed: Boolean? = null,
    @SerializedName("delayMinutes") val delayMinutes: Int? = null,
    @SerializedName("compensationStatus") val compensationStatus: String? = null,
    @SerializedName("complaintText") val complaintText: String? = null,
    @SerializedName("counterComplaint") val counterComplaint: String? = null,
    @SerializedName("compensationAmount") val compensationAmount: Int? = null,
    @SerializedName("createdAt") val createdAt: Date? = null
)

data class CreateOrderRequest(
    @SerializedName("appName") val appName: String,
    @SerializedName("orderId") val orderId: String,
    @SerializedName("currentStatus") val currentStatus: String,
    val eta: String, // ISO date string
    @SerializedName("restaurantName") val restaurantName: String? = null,
    @SerializedName("orderTotal") val orderTotal: Int? = null
)

data class PendingClaim(
    val id: Int,
    @SerializedName("appName") val appName: String,
    @SerializedName("orderId") val orderId: String,
    @SerializedName("complaintText") val complaintText: String,
    @SerializedName("delayMinutes") val delayMinutes: Int
)

data class SupportReplyRequest(
    @SerializedName("supportReply") val supportReply: String
)

data class SuccessRequest(
    @SerializedName("compensationAmount") val compensationAmount: Int
)

data class StatsResponse(
    @SerializedName("totalRecovered") val totalRecovered: Int,
    @SerializedName("totalClaims") val totalClaims: Int,
    @SerializedName("escalatedWins") val escalatedWins: Int,
    @SerializedName("averagePerClaim") val averagePerClaim: Int
)

enum class CompensationStatus(val value: String) {
    MONITORING("monitoring"),
    INTERVENING("intervening"),
    CLAIM_READY("claim_ready"),
    AWAITING_REPLY("awaiting_reply"),
    ESCALATING("escalating"),
    ESCALATED("escalated"),
    SUCCESS("success")
}

// AI Screen Analysis Models
data class AnalyzeScreenRequest(
    @SerializedName("screenText") val screenText: String,
    @SerializedName("packageName") val packageName: String? = null
)

data class AnalyzeScreenResponse(
    val detected: Boolean,
    val action: String? = null,  // "created", "updated"
    val reason: String? = null,  // "no_text", "parse_error", "not_order_screen"
    val order: Order? = null,
    val extracted: ExtractedOrderInfo? = null
)

data class ExtractedOrderInfo(
    val appName: String?,
    val orderId: String?,
    val status: String?,
    val eta: String?,
    val confidence: Double?
)

// Network Interception Models
data class InterceptedDataRequest(
    @SerializedName("payload") val payload: String
)

data class InterceptedDataResponse(
    val success: Boolean,
    val message: String? = null,
    val order: Order? = null,
    val action: String? = null
)

// SSL Proxy Interception Models
data class InterceptedOrderRequest(
    @SerializedName("orderId") val orderId: String,
    @SerializedName("restaurantName") val restaurantName: String,
    @SerializedName("deliveryApp") val deliveryApp: String,
    @SerializedName("status") val status: String,
    @SerializedName("eta") val eta: String?,
    @SerializedName("rawJson") val rawJson: String
)

data class RawInterceptRequest(
    @SerializedName("hostname") val hostname: String,
    @SerializedName("path") val path: String,
    @SerializedName("method") val method: String,
    @SerializedName("responseBody") val responseBody: String
)
