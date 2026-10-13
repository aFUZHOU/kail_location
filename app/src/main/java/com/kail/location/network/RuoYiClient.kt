package com.kail.location.network

import com.kail.location.BuildConfig
import com.kail.location.utils.KailLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RuoYiClient {

    private const val TAG = "RuoYiClient"
    private const val JSON_TYPE = "application/json"

    var baseUrl: String = BuildConfig.APP_API_URL

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class AuthResult(
        val token: String,
        val email: String,
        val id: String,
        val verified: Boolean
    )

    /**
     * 游客配额信息。
     * @param used 已用次数
     * @param limit 总上限；-1 表示服务端开启了无限次补偿
     * @param remaining 剩余次数；-1 表示无限
     */
    data class GuestQuotaInfo(
        val used: Int,
        val limit: Int,
        val remaining: Int
    )

    /** 游客免费次数已用完（服务端返回 1401 时抛出，用于与网络异常区分）。 */
    class GuestQuotaExhaustedException(message: String) : Exception(message)

    /** 服务端"游客次数用尽"的业务码。 */
    private const val GUEST_QUOTA_EXHAUSTED_CODE = 1401

    /**
     * 服务端缺失 limit 字段时的兜底值，需与 GuestModeManager.GUEST_FREE_TOTAL 保持一致。
     * 服务端 GuestQuotaVO 恒返回该字段，此处仅防御解析异常（缺省 0 会误判为"无次数可用"）。
     * 当前为 -1：游客无限次（与 GuestModeManager.GUEST_FREE_TOTAL 同步调整）。
     */
    private const val GUEST_FALLBACK_LIMIT = -1

    private fun Request.Builder.withTenant(): Request.Builder {
        return this.header("tenant-id", "1")
    }

    private fun Request.Builder.withAuth(token: String): Request.Builder {
        return this.header("Authorization", "Bearer $token")
    }

    /** 游客接口无需登录 token，通过 guest-id / device-id 头标识身份。 */
    private fun Request.Builder.withGuest(guestId: String, deviceId: String): Request.Builder {
        return this.header("guest-id", guestId).header("device-id", deviceId)
    }

    fun checkSimulation(token: String): Result<Int> {
        return runCatching {
            val url = "$baseUrl/member/simulation/check"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withAuth(token)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "获取剩余次数失败"))
            }
            val remaining = root.getJSONObject("data").optInt("remainingToday", 0)
            KailLog.i(null, TAG, "checkSimulation: http=${response.code} code=$code remainingToday=$remaining")
            remaining
        }.onFailure { KailLog.w(null, TAG, "checkSimulation failed: ${it.message}") }
    }

    fun useSimulation(token: String): Result<Unit> {
        return runCatching {
            val url = "$baseUrl/member/simulation/use"
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .withAuth(token)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            KailLog.i(null, TAG, "useSimulation: http=${response.code} code=$code msg=${root.optString("msg", "")}")
            if (code != 0) {
                throw Exception(root.optString("msg", "模拟次数已用完"))
            }
        }.onFailure { KailLog.w(null, TAG, "useSimulation failed: ${it.message}") }
    }

    /**
     * 查询游客配额（只读，不扣次数）。
     * 未注册过的 guestId 会在服务端自动创建记录并返回全量配额。
     */
    fun guestCheck(guestId: String, deviceId: String): Result<GuestQuotaInfo> {
        return runCatching {
            val url = "$baseUrl/guest/simulation/check"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withGuest(guestId, deviceId)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "获取游客剩余次数失败"))
            }
            val data = root.getJSONObject("data")
            val info = GuestQuotaInfo(
                used = data.optInt("used", 0),
                limit = data.optInt("limit", GUEST_FALLBACK_LIMIT),
                remaining = data.optInt("remaining", 0)
            )
            KailLog.i(null, TAG, "guestCheck: http=${response.code} used=${info.used} limit=${info.limit} remaining=${info.remaining}")
            info
        }.onFailure { KailLog.w(null, TAG, "guestCheck failed: ${it.message}") }
    }

    /**
     * 游客扣减一次模拟机会（服务端原子扣减，越界返回 1401）。
     * 抛出 [GuestQuotaExhaustedException] 表示次数已用尽（与网络异常区分）。
     */
    fun guestUse(guestId: String, deviceId: String): Result<GuestQuotaInfo> {
        return runCatching {
            val url = "$baseUrl/guest/simulation/use"
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .withGuest(guestId, deviceId)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code == GUEST_QUOTA_EXHAUSTED_CODE) {
                throw GuestQuotaExhaustedException(root.optString("msg", "游客免费次数已用完"))
            }
            if (code != 0) {
                throw Exception(root.optString("msg", "扣减游客次数失败"))
            }
            val data = root.getJSONObject("data")
            val info = GuestQuotaInfo(
                used = data.optInt("used", 0),
                limit = data.optInt("limit", GUEST_FALLBACK_LIMIT),
                remaining = data.optInt("remaining", 0)
            )
            KailLog.i(null, TAG, "guestUse: http=${response.code} used=${info.used} limit=${info.limit} remaining=${info.remaining}")
            info
        }.onFailure { e ->
            if (e is GuestQuotaExhaustedException) {
                KailLog.i(null, TAG, "guestUse: guest quota exhausted")
            } else {
                KailLog.w(null, TAG, "guestUse failed: ${e.message}")
            }
        }
    }

    /**
     * 游客离线消耗对账：把客户端离线期间累计的 [delta] 次消耗补记到服务端。
     * 服务端原子累加并受上限保护（超出上限则截断），返回最新配额。
     */
    fun guestSync(guestId: String, delta: Int, deviceId: String): Result<GuestQuotaInfo> {
        return runCatching {
            val url = "$baseUrl/guest/simulation/sync"
            val json = JSONObject().apply { put("delta", delta) }
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .withGuest(guestId, deviceId)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "对账游客次数失败"))
            }
            val data = root.getJSONObject("data")
            val info = GuestQuotaInfo(
                used = data.optInt("used", 0),
                limit = data.optInt("limit", GUEST_FALLBACK_LIMIT),
                remaining = data.optInt("remaining", 0)
            )
            KailLog.i(null, TAG, "guestSync: http=${response.code} delta=$delta used=${info.used} limit=${info.limit}")
            info
        }.onFailure { KailLog.w(null, TAG, "guestSync failed: ${it.message}") }
    }

    fun sendMailCode(mail: String, scene: Int): Result<Unit> {
        return runCatching {
            val url = "$baseUrl/member/auth/send-mail-code"
            val json = JSONObject().apply {
                put("mail", mail)
                put("scene", scene)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            KailLog.i(null, TAG, "sendMailCode(scene=$scene): http=${response.code} code=$code")
            if (code != 0) {
                throw Exception(root.optString("msg", "发送验证码失败"))
            }
        }.onFailure { KailLog.w(null, TAG, "sendMailCode failed: ${it.message}") }
    }

    fun loginByMail(mail: String, code: String): Result<AuthResult> {
        return runCatching {
            val url = "$baseUrl/member/auth/mail-login"
            val json = JSONObject().apply {
                put("mail", mail)
                put("code", code)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE.toMediaType()))
                .header("Content-Type", JSON_TYPE)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val respCode = root.optInt("code", -1)
            // 不打印 body：包含 accessToken。
            KailLog.i(null, TAG, "loginByMail: http=${response.code} code=$respCode")
            if (respCode != 0) {
                throw Exception(root.optString("msg", "登录失败"))
            }

            val data = root.getJSONObject("data")
            AuthResult(
                token = data.getString("accessToken"),
                email = mail,
                id = data.optString("userId", ""),
                verified = true
            )
        }
    }

    data class SubscriptionStatus(
        val active: Boolean,
        val planName: String,
        val expiresAt: String,
        val daysRemaining: Int
    )

    data class SubscriptionPlan(
        val id: Long,
        val name: String,
        val description: String,
        val price: Int,
        val currency: String,
        val billingInterval: String,
        val billingIntervalCount: Int,
        val trialDays: Int
    )

    suspend fun getPlans(token: String): Result<List<SubscriptionPlan>> {
        return runCatching {
            val url = "$baseUrl/member/subscription-plan/list"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withAuth(token)
                .withTenant()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val respCode = root.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(root.optString("msg", "获取套餐列表失败"))
            }
            val arr = root.getJSONArray("data")
            val plans = mutableListOf<SubscriptionPlan>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                plans.add(SubscriptionPlan(
                    id = item.getLong("id"),
                    name = item.getString("name"),
                    description = item.optString("description", ""),
                    price = item.getInt("price"),
                    currency = item.optString("currency", "CNY"),
                    billingInterval = item.optString("billingInterval", "month"),
                    billingIntervalCount = item.optInt("billingIntervalCount", 1),
                    trialDays = item.optInt("trialDays", 0)
                ))
            }
            KailLog.i(null, TAG, "getPlans: http=${response.code} code=$respCode count=${plans.size}")
            plans
        }.onFailure { KailLog.w(null, TAG, "getPlans failed: ${it.message}") }
    }

    data class NoticeInfo(
        val id: Long,
        val title: String,
        val type: Int,
        val content: String,
        val createTime: String
    )

    suspend fun getNoticeList(): Result<List<NoticeInfo>> {
        return runCatching {
            val url = "$baseUrl/system/notice/list"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "获取公告失败"))
            }
            val arr = root.optJSONArray("data") ?: return@runCatching emptyList()
            val list = mutableListOf<NoticeInfo>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(NoticeInfo(
                    id = item.getLong("id"),
                    title = item.optString("title", ""),
                    type = item.optInt("type", 0),
                    content = item.optString("content", ""),
                    createTime = item.optString("createTime", "")
                ))
            }
            list
        }.onFailure { KailLog.w(null, TAG, "getNoticeList failed: ${it.message}") }
    }

    data class FaqItem(
        val id: Long,
        val title: String,
        val answerMd: String,
        val sort: Int
    )

    suspend fun getFaqList(): Result<List<FaqItem>> {
        return runCatching {
            val url = "$baseUrl/system/faq/list"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw Exception(root.optString("msg", "获取常见问题失败"))
            }
            val arr = root.optJSONArray("data") ?: return@runCatching emptyList()
            val list = mutableListOf<FaqItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(FaqItem(
                    id = item.getLong("id"),
                    title = item.optString("title", ""),
                    answerMd = item.optString("answerMd", ""),
                    sort = item.optInt("sort", 0)
                ))
            }
            list
        }.onFailure { KailLog.w(null, TAG, "getFaqList failed: ${it.message}") }
    }

    suspend fun getSubscriptionStatus(token: String): Result<SubscriptionStatus> {
        return runCatching {
            val url = "$baseUrl/member/subscription/status"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Content-Type", JSON_TYPE)
                .withAuth(token)
                .withTenant()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response")

            val root = JSONObject(body)
            val respCode = root.optInt("code", -1)
            if (respCode != 0) {
                throw Exception(root.optString("msg", "获取订阅状态失败"))
            }

            val data = root.getJSONObject("data")
            val status = SubscriptionStatus(
                active = data.optBoolean("active", false),
                planName = data.optString("planName", ""),
                expiresAt = data.optString("expiresAt", ""),
                daysRemaining = data.optInt("daysRemaining", 0)
            )
            KailLog.i(null, TAG, "getSubscriptionStatus: http=${response.code} active=${status.active} daysRemaining=${status.daysRemaining}")
            status
        }.onFailure { KailLog.w(null, TAG, "getSubscriptionStatus failed: ${it.message}") }
    }

}
