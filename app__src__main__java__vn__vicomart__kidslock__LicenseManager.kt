package vn.vicomart.kidslock

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * License / trial status, checked against the Supabase RPCs kl_check and kl_activate.
 * Status values: licensed | trial | expired | unknown | dev (backend not configured).
 */
class LicenseManager(ctx: Context) {

    data class State(
        val status: String,
        val plan: String? = null,
        val expiresAtMs: Long? = null,
        val trialEndsMs: Long? = null,
        /** Error code from the server or "network" — mapped to a localized string by the UI. */
        val error: String? = null
    )

    private val app = ctx.applicationContext
    private val sp = app.getSharedPreferences("kidslock_license", Context.MODE_PRIVATE)

    val configured: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    @SuppressLint("HardwareIds")
    val deviceId: String =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun cached(): State {
        if (!configured) return State("dev")
        return State(
            status = sp.getString("status", "unknown") ?: "unknown",
            plan = sp.getString("plan", null),
            expiresAtMs = sp.getLong("expires", -1L).takeIf { it > 0 },
            trialEndsMs = sp.getLong("trial_ends", -1L).takeIf { it > 0 }
        )
    }

    /** True when protection may run. Works offline for up to 7 days after the last good check. */
    fun isUsable(): Boolean {
        if (!configured) return true
        val now = System.currentTimeMillis()
        val lastOk = sp.getLong("last_ok", 0L)
        if (now - lastOk > OFFLINE_GRACE_MS) return false
        val s = cached()
        return when (s.status) {
            "licensed" -> s.expiresAtMs == null || s.expiresAtMs > now
            "trial" -> (s.trialEndsMs ?: 0L) > now
            else -> false
        }
    }

    suspend fun refresh(): State {
        if (!configured) return State("dev")
        val body = JSONObject()
            .put("p_device", deviceId)
            .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("p_version", BuildConfig.VERSION_NAME)
        return call("kl_check", body)
    }

    suspend fun activate(key: String): State {
        if (!configured) return State("dev")
        val body = JSONObject()
            .put("p_key", key.trim())
            .put("p_device", deviceId)
            .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("p_version", BuildConfig.VERSION_NAME)
        return call("kl_activate", body)
    }

    private suspend fun call(fn: String, body: JSONObject): State = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/rpc/$fn")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return@withContext cached().copy(error = "network")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val status = o.optString("status", "unknown")
            if (status == "error") return@withContext cached().copy(error = o.optStringOrNull("error") ?: "unknown")
            val s = State(
                status = status,
                plan = o.optStringOrNull("plan"),
                expiresAtMs = o.optLongOrNull("expires_at_ms"),
                trialEndsMs = o.optLongOrNull("trial_ends_ms"),
                error = o.optStringOrNull("error")
            )
            save(s)
            s
        } catch (_: Exception) {
            cached().copy(error = "network")
        } finally {
            conn?.disconnect()
        }
    }

    private fun save(s: State) {
        sp.edit()
            .putString("status", s.status)
            .putString("plan", s.plan)
            .putLong("expires", s.expiresAtMs ?: -1L)
            .putLong("trial_ends", s.trialEndsMs ?: -1L)
            .putLong("last_ok", System.currentTimeMillis())
            .apply()
    }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k)

    private fun JSONObject.optLongOrNull(k: String): Long? =
        if (!has(k) || isNull(k)) null else optLong(k)

    companion object {
        private const val OFFLINE_GRACE_MS = 7L * 24 * 3600 * 1000
    }
}
