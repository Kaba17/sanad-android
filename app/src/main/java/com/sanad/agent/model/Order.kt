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

// Test Mode Models
data class TestOrderRequest(
    val scenario: String // "delayed", "on_time", "soon"
)

data class TestOrderResponse(
    val success: Boolean,
    val order: Order?,
    val scenario: String?
)

data class ClearTestResponse(
    val success: Boolean,
    val deleted: Int
)
