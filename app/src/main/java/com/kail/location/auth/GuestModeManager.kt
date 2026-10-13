package com.kail.location.auth

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import com.kail.location.network.RuoYiClient
import com.kail.location.network.RuoYiClient.GuestQuotaExhaustedException
import com.kail.location.utils.KailLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 游客模式管理器。
 *
 * 未登录用户无需注册/登录即可使用模拟功能。当前版本（自用版）游客
 * 模拟机会为**无限次**（[GUEST_FREE_TOTAL] = -1，负值即无限的约定）。
 * 设计要点：
 *
 * 1. **游客身份**：首次使用时生成 UUID（`guest_id`）并持久化在独立
 *    SharedPreferences（`guest_prefs`）中。登录/退出登录不影响该 ID，
 *    避免"退出登录重置配额"的绕过手段；清除应用数据/重装会生成新 ID，
 *    但服务端仍保留旧 ID 的已用次数（且可按设备指纹限流）。
 * 2. **服务端权威**：已用次数以服务端记录为准，本地缓存仅作离线兜底与展示。
 * 3. **离线兜底**：网络不可用时按本地缓存校验与扣减，同时记录 pending 增量，
 *    下次联网时通过 `guestSync` 对账（服务端原子累加并受上限保护）。
 * 4. **无限次保障**（[GUEST_FREE_TOTAL] < 0 时生效）：
 *    - 本地 limit 恒钳制为 -1，无视旧服务端返回的有限 limit；
 *    - 服务端返回"次数用尽"（1401）时放行而非拦截；
 *    - 离线兜底不扣减、不记 pending。
 *    将 [GUEST_FREE_TOTAL] 改回正数即可恢复限量模式，全部逻辑自动回归。
 * 5. **次数用尽引导**：仅限量模式可用尽；用尽时自增 [exhaustedEventId]，
 *    由 UI 弹出引导对话框（见 `views/common/GuestQuotaDialog.kt`）。
 */
object GuestModeManager {
    private const val TAG = "GuestModeManager"

    private const val PREFS_NAME = "guest_prefs"
    private const val KEY_GUEST_ID = "guest_id"
    private const val KEY_USED = "guest_used"
    private const val KEY_LIMIT = "guest_limit"
    private const val KEY_PENDING = "guest_pending"
    private const val KEY_LAST_SYNC = "guest_last_sync_ts"
    private const val KEY_SYNCED_ONCE = "guest_synced_once"

    /**
     * 每位游客的免费模拟总次数。
     * -1 = 无限次（当前自用版配置）；改为正数即恢复限量模式。
     */
    const val GUEST_FREE_TOTAL = -1

    /** 与服务端同步的最小间隔（毫秒），避免每次开始模拟都打网络。 */
    private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 对账互斥锁：串行化 check→sync 流程，防止两个并发 syncIfNeeded
     * 把同一批 pending 重复对账到服务端。
     */
    private val syncMutex = Mutex()

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    /** 设备指纹（ANDROID_ID），随游客请求上报，供服务端限流/风控。 */
    private var cachedDeviceId: String = ""

    // ---- 可观察状态（供 UI 展示，风格同 AuthManager）----
    private val _guestUsed = mutableStateOf(0)
    val guestUsed: Int get() = _guestUsed.value

    private val _guestLimit = mutableStateOf(GUEST_FREE_TOTAL)
    val guestLimit: Int get() = _guestLimit.value

    /** 剩余次数；-1 表示服务端开启了无限次补偿。 */
    private val _guestRemaining = mutableStateOf(GUEST_FREE_TOTAL)
    val guestRemaining: Int get() = _guestRemaining.value
    val guestRemainingState get() = _guestRemaining
    val guestUsedState get() = _guestUsed

    /**
     * 次数用尽事件计数器：每次用尽 +1。
     * UI 侧对比上次处理的事件 ID 来决定是否弹出引导对话框，可重复触发。
     */
    private val _exhaustedEventId = mutableStateOf(0L)
    val exhaustedEventId get() = _exhaustedEventId

    /** 服务端是否已成功同步过；false 时本地缓存为乐观初始值（全新游客或从未联网）。 */
    @Volatile
    private var syncedOnce = false

    /** 当前游客 ID（init 后可用）。 */
    val guestId: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_GUEST_ID, "") ?: "" else ""

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cachedDeviceId = readDeviceId(appContext)
        synchronized(lock) {
            if (prefs.getString(KEY_GUEST_ID, null).isNullOrBlank()) {
                prefs.edit().putString(KEY_GUEST_ID, java.util.UUID.randomUUID().toString()).apply()
            }
            syncedOnce = prefs.getBoolean(KEY_SYNCED_ONCE, false)
            reloadFromPrefsLocked()
        }
        // 启动时后台同步一次：清除数据/重装后可从服务端恢复已用次数
        scope.launch { syncIfNeeded(force = true) }
    }

    /**
     * 本地配置为无限次（[GUEST_FREE_TOTAL] < 0）时，无视服务端返回的
     * limit，恒钳制为 -1；限量模式下原样采用服务端值。
     */
    private fun effectiveLimit(serverLimit: Int): Int =
        if (GUEST_FREE_TOTAL < 0) -1 else serverLimit

    /**
     * 游客是否可以开始模拟（只检查，不扣次数）。
     * 服务端不可达时退化为本地缓存校验；本地缓存也从未同步过时按初始值放行
     * （全新游客首次离线使用），联网后由 [syncIfNeeded] 对账兜底。
     */
    suspend fun canStartSimulation(context: Context): Boolean {
        // 无限次模式（GUEST_FREE_TOTAL < 0）：跳过一切配额判断，直接放行。
        // 不触发 syncIfNeeded 以避免无意义的网络请求；限量期残留的 pending 由
        // init() 启动时的 force 同步清理。
        if (GUEST_FREE_TOTAL < 0) return true
        syncIfNeeded()
        val (used, limit) = synchronized(lock) {
            prefs.getInt(KEY_USED, 0) to prefs.getInt(KEY_LIMIT, GUEST_FREE_TOTAL)
        }
        val remaining = if (limit < 0) -1 else limit - used
        KailLog.i(context, TAG, "canStartSimulation: used=$used limit=$limit remaining=$remaining")
        // remaining != 0：-1 表示服务端无限次补偿，>0 表示还有次数；仅剩 0 次时拦截
        if (remaining != 0) return true
        onExhausted(context)
        return false
    }

    /**
     * 游客开始模拟时扣减一次。
     * - 服务端扣减成功：以服务端返回为准刷新本地（保留未对账的 pending 增量）。
     * - 服务端返回次数用尽（1401）：本地置为用尽并触发引导。
     * - 网络等异常：离线兜底——本地未用尽则扣一次并记 pending，否则按用尽处理。
     */
    suspend fun consumeSimulation(context: Context): Boolean {
        // 无限次模式（GUEST_FREE_TOTAL < 0）：无需扣减、不打网络、不记 pending，直接放行
        if (GUEST_FREE_TOTAL < 0) return true
        val id = guestId
        if (id.isBlank()) {
            KailLog.w(context, TAG, "consumeSimulation=false: guest id not ready")
            return false
        }
        val result = RuoYiClient.guestUse(id, cachedDeviceId)
        if (result.isSuccess) {
            val info = result.getOrThrow()
            val pending = synchronized(lock) { prefs.getInt(KEY_PENDING, 0) }
            // 服务端返回值不含尚未对账的离线增量，本地视图需要叠加，避免倒退
            val effectiveUsed = info.used + pending
            synchronized(lock) {
                prefs.edit()
                    .putInt(KEY_USED, effectiveUsed.coerceAtLeast(0))
                    .putInt(KEY_LIMIT, effectiveLimit(info.limit))
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .putBoolean(KEY_SYNCED_ONCE, true)
                    .apply()
                syncedOnce = true
                reloadFromPrefsLocked()
            }
            KailLog.i(context, TAG, "consumeSimulation: server consumed, used=${info.used}(+$pending pending) limit=${effectiveLimit(info.limit)}")
            return true
        }
        val ex = result.exceptionOrNull()
        if (ex is GuestQuotaExhaustedException) {
            if (GUEST_FREE_TOTAL < 0) {
                // 无限次模式：服务端若仍是旧限额返回 1401，以本地无限为准放行
                KailLog.i(context, TAG, "consumeSimulation: server says exhausted but guest unlimited locally, allow")
                return true
            }
            synchronized(lock) {
                val limit = prefs.getInt(KEY_LIMIT, GUEST_FREE_TOTAL)
                    .let { if (it < 0) GUEST_FREE_TOTAL else it }
                prefs.edit().putInt(KEY_USED, limit).apply()
                reloadFromPrefsLocked()
            }
            KailLog.i(context, TAG, "consumeSimulation=false: server says guest quota exhausted")
            onExhausted(context)
            return false
        }
        KailLog.w(context, TAG, "consumeSimulation: server unreachable (${ex?.message}), fallback to local")
        return offlineConsume(context)
    }

    /** 剩余次数（-1 表示无限）。 */
    fun remaining(): Int = _guestRemaining.value

    /** 游客次数是否已用尽（无限次时恒为 false）。 */
    fun isExhausted(): Boolean {
        val limit = _guestLimit.value
        if (limit < 0) return false
        return _guestUsed.value >= limit
    }

    /**
     * 与服务端同步（节流）：拉取已用次数，并把离线期间产生的 pending 增量对账上去。
     * @return true 表示本次成功访问到服务端（或按节流策略跳过）。
     */
    suspend fun syncIfNeeded(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val id = guestId
        if (id.isBlank()) return@withContext false
        val need = synchronized(lock) { force || shouldSyncLocked() }
        if (!need) return@withContext true

        // 串行化对账：避免并发 sync 把同一批 pending 重复上报
        syncMutex.withLock {
            val check = RuoYiClient.guestCheck(id, cachedDeviceId)
            if (check.isFailure) {
                KailLog.w(null, TAG, "syncIfNeeded: check failed: ${check.exceptionOrNull()?.message}")
                return@withLock false
            }
            var info = check.getOrThrow()
            val pending = synchronized(lock) { prefs.getInt(KEY_PENDING, 0) }
            var flushed = 0
            if (pending > 0) {
                val sync = RuoYiClient.guestSync(id, pending, cachedDeviceId)
                if (sync.isSuccess) {
                    info = sync.getOrThrow()
                    flushed = pending
                } else {
                    KailLog.w(null, TAG, "syncIfNeeded: flush pending=$pending failed: ${sync.exceptionOrNull()?.message}")
                    // 对账失败时本地叠加 pending，保证已发生的离线消耗不丢失
                    info = info.copy(used = info.used + pending)
                }
            }
            synchronized(lock) {
                prefs.edit()
                    .putInt(KEY_USED, info.used.coerceAtLeast(0))
                    .putInt(KEY_LIMIT, effectiveLimit(info.limit))
                    // 只扣减已成功对账的部分：对账期间新产生的离线消耗继续保留待下次同步
                    .putInt(KEY_PENDING, (prefs.getInt(KEY_PENDING, 0) - flushed).coerceAtLeast(0))
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .putBoolean(KEY_SYNCED_ONCE, true)
                    .apply()
                syncedOnce = true
                reloadFromPrefsLocked()
            }
            KailLog.i(null, TAG, "syncIfNeeded: synced used=${info.used} limit=${effectiveLimit(info.limit)} flushed=$flushed")
            true
        }
    }

    // ----------------------------------------------------------------

    private fun shouldSyncLocked(): Boolean {
        if (prefs.getInt(KEY_PENDING, 0) > 0) return true
        val last = prefs.getLong(KEY_LAST_SYNC, 0L)
        return System.currentTimeMillis() - last >= SYNC_INTERVAL_MS
    }

    /** 必须在持有 [lock] 时调用。 */
    private fun reloadFromPrefsLocked() {
        val used = prefs.getInt(KEY_USED, 0).coerceAtLeast(0)
        val limit = prefs.getInt(KEY_LIMIT, GUEST_FREE_TOTAL)
        _guestUsed.value = used
        _guestLimit.value = limit
        _guestRemaining.value = if (limit < 0) -1 else (limit - used).coerceAtLeast(0)
    }

    /** 网络不可用时的本地扣减：受本地缓存的上限保护，并记录 pending 待对账。 */
    private suspend fun offlineConsume(context: Context): Boolean {
        val consumed = synchronized(lock) {
            val used = prefs.getInt(KEY_USED, 0)
            val limit = prefs.getInt(KEY_LIMIT, GUEST_FREE_TOTAL)
            val unlimited = limit < 0
            if (!unlimited && used >= limit) {
                false
            } else {
                prefs.edit()
                    .putInt(KEY_USED, if (unlimited) used else used + 1)
                    .putInt(KEY_PENDING, if (unlimited) prefs.getInt(KEY_PENDING, 0) else prefs.getInt(KEY_PENDING, 0) + 1)
                    .apply()
                reloadFromPrefsLocked()
                true
            }
        }
        if (consumed) {
            KailLog.w(null, TAG, "offlineConsume: consumed locally, pending flush on next sync")
        } else {
            KailLog.i(null, TAG, "offlineConsume=false: local quota exhausted")
            onExhausted(context)
        }
        return consumed
    }

    /** 次数用尽：触发 UI 引导事件 + Toast 提示。 */
    private suspend fun onExhausted(context: Context) {
        _exhaustedEventId.value = _exhaustedEventId.value + 1
        KailLog.i(context, TAG, "guest quota exhausted, raise login guidance (event=${_exhaustedEventId.value})")
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.kail.location.R.string.usage_guest_exhausted),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readDeviceId(context: Context): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: ""
    }
}
