package com.sanad.agent.api

import com.sanad.agent.model.*
import retrofit2.Response
import retrofit2.http.*

interface SanadApi {
    
    @POST("api/orders")
    suspend fun createOrder(@Body order: CreateOrderRequest): Response<Order>
    
    @GET("api/orders")
    suspend fun getOrders(): Response<List<Order>>
    
    @GET("api/orders/pending-claims")
    suspend fun getPendingClaims(): Response<List<PendingClaim>>
    
    @GET("api/orders/pending-escalations")
    suspend fun getPendingEscalations(): Response<List<PendingClaim>>
    
    @POST("api/orders/{id}/claim-sent")
    suspend fun markClaimSent(@Path("id") orderId: Int): Response<Order>
    
    @POST("api/orders/{id}/support-reply")
    suspend fun submitSupportReply(
        @Path("id") orderId: Int,
        @Body reply: SupportReplyRequest
    ): Response<Order>
    
    @POST("api/orders/{id}/success")
    suspend fun markSuccess(
        @Path("id") orderId: Int,
        @Body success: SuccessRequest
    ): Response<Order>
    
    @GET("api/stats")
    suspend fun getStats(): Response<StatsResponse>
    
    // AI Screen Analysis
    @POST("api/analyze-screen")
    suspend fun analyzeScreen(@Body request: AnalyzeScreenRequest): Response<AnalyzeScreenResponse>
    
    // Network Interception - Send raw intercepted data
    @POST("api/intercept/order")
    suspend fun sendInterceptedData(@Body data: InterceptedDataRequest): Response<InterceptedDataResponse>
    
    // SSL Proxy - Send parsed order from network interception
    @POST("api/intercept/ssl-order")
    suspend fun sendInterceptedOrder(@Body data: InterceptedOrderRequest): Response<Order>
    
    // SSL Proxy - Send raw intercept for analysis
    @POST("api/intercept/raw")
    suspend fun sendRawIntercept(@Body data: RawInterceptRequest): Response<InterceptedDataResponse>
}
