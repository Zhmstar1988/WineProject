package com.wine.dispenser.network

import com.google.gson.GsonBuilder
import com.wine.dispenser.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// 统一响应包装
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?
)

// 用户登录
data class LoginResponse(
    val token: String,
    val userId: Long,
    val role: Int,
    val ageVerified: Boolean,
    val nickname: String
)

// 酒单
data class MenuResponse(
    val barId: Long,
    val barName: String,
    val wines: List<WineItem>
)

data class WineItem(
    val wineSkuId: Long,
    val wineName: String,
    val origin: String?,
    val vintage: Int?,
    val grapeType: String?,
    val alcohol: String?,
    val coverImage: String?,
    val description: String?,
    val currentCapacity: Int?,
    val cupOptions: List<CupOption>
)

data class CupOption(
    val volumeMl: Int,
    val volumeName: String,
    val price: Double,
    val slotNo: Int?,
    val dispenserId: Long?
)

// 订单
data class OrderResponse(
    val orderNo: String,
    val status: Int,
    val statusName: String,
    val barId: Long,
    val dispenserId: Long,
    val slotNo: Int,
    val wineSkuId: Long,
    val volumeMl: Int,
    val originalAmount: Double,
    val discountAmount: Double,
    val paidAmount: Double,
    val cashierUrl: String?
)

// 订单状态码（与后端 OrderStatusEnum 对齐）
object OrderStatus {
    const val PENDING = 1   // 待支付
    const val PAYING = 2    // 支付中
    const val PAID = 3      // 已付款/待履约
    const val COMPLETED = 4 // 已完成
    const val REFUNDED = 5  // 已退款
}

interface WineApi {

    @POST("/api/auth/sms/send")
    suspend fun sendSms(@Body body: Map<String, @JvmSuppressWildcards String>): ApiResponse<Map<String, Any>>

    @POST("/api/auth/login")
    suspend fun login(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<LoginResponse>

    @POST("/api/auth/age-verify")
    suspend fun verifyAge(
        @Header("Authorization") token: String,
        @Body body: Map<String, @JvmSuppressWildcards String>
    ): ApiResponse<Map<String, Any>>

    @GET("/api/menu/bar/{barCode}")
    suspend fun getMenu(@Path("barCode") barCode: String): ApiResponse<MenuResponse>

    @POST("/api/order/create")
    suspend fun createOrder(
        @Header("Authorization") token: String,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): ApiResponse<OrderResponse>

    /** 查询订单详情（含最新状态） */
    @GET("/api/order/{orderNo}")
    suspend fun getOrder(
        @Header("Authorization") token: String,
        @Path("orderNo") orderNo: String
    ): ApiResponse<OrderResponse>

    /** 发起支付，返回通联收银台 URL */
    @POST("/api/payment/pay/{orderNo}")
    suspend fun pay(
        @Header("Authorization") token: String,
        @Path("orderNo") orderNo: String
    ): ApiResponse<OrderResponse>

    @POST("/api/dispense/start/{orderNo}")
    suspend fun startDispense(
        @Header("Authorization") token: String,
        @Path("orderNo") orderNo: String
    ): ApiResponse<Any>

    @POST("/api/dispense/callback")
    suspend fun dispenseCallback(@Body body: Map<String, Any>): ApiResponse<Any>
}

object ApiClient {
    private val BASE_URL = BuildConfig.API_BASE_URL

    val api: WineApi by lazy {
        val gson = GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(WineApi::class.java)
    }
}
