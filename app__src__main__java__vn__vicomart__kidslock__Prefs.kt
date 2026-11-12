package vn.vicomart.kidslock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

sealed class PinResult {
    object Ok : PinResult()
    object Wrong : PinResult()
    data class Locked(val remainingMs: Long) : PinResult()
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("kidslock", Context.MODE_PRIVATE)

    // ---------- general ----------
    var consentAccepted: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit().putBoolean("consent", v).apply()

    var langTag: String
        get() = sp.getString("lang", "") ?: ""
        set(v) = sp.edit().putString("lang", v).apply()

    var protectionEnabled: Boolean
        get() = sp.getBoolean("protection", false)
        set(v) = sp.edit().putBoolean("protection", v).apply()

    /** Persisted so the lock screen comes back if the service is restarted. */
    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()

    var unlockedUntil: Long
        get() = sp.getLong("unlocked_until", 0L)
        set(v) = sp.edit().putLong("unlocked_until", v).apply()

    // ---------- detection settings ----------
    /** Person is treated as a child when estimated age is below this. */
    var ageThreshold: Int
        get() = sp.getInt("age_threshold", 12)
        set(v) = sp.edit().putInt("age_threshold", v).apply()

    /** 0 = low (3 confirmations), 1 = medium (2), 2 = high (1). */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 1)
        set(v) = sp.edit().putInt("sensitivity", v).apply()

    var intervalSec: Int
        get() = sp.getInt("interval_sec", 15)
        set(v) = sp.edit().putInt("interval_sec", v).apply()

    var graceMin: Int
        get() = sp.getInt("grace_min", 15)
        set(v) = sp.edit().putInt("grace_min", v).apply()

    // ---------- calibration for the basic (no-model) AI ----------
    fun calCount(child: Boolean): Int = sp.getInt(if (child) "cal_c_n" else "cal_a_n", 0)

    fun calCentroid(child: Boolean): FloatArray? {
        val n = calCount(child)
        if (n <= 0) return null
        val raw = sp.getString(if (child) "cal_c_sum" else "cal_a_sum", null) ?: return null
        val sum = raw.split(",").mapNotNull { it.toFloatOrNull() }
        if (sum.isEmpty()) return null
        return FloatArray(sum.size) { sum[it] / n }
    }

    /** Adds one session's averaged features (running mean over sessions). */
    fun addCalibration(child: Boolean, features: FloatArray) {
        val keySum = if (child) "cal_c_sum" else "cal_a_sum"
        val keyN = if (child) "cal_c_n" else "cal_a_n"
        val n = sp.getInt(keyN, 0)
        val old = sp.getString(keySum, null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
        val sum = FloatArray(features.size) { i ->
            features[i] + (if (old != null && old.size == features.size) old[i] else 0f)
        }
        val newN = if (old != null && old.size == features.size) n + 1 else 1
        sp.edit().putString(keySum, sum.joinToString(",")).putInt(keyN, newN).apply()
    }

    fun clearCalibration() {
        sp.edit().remove("cal_c_sum").remove("cal_c_n").remove("cal_a_sum").remove("cal_a_n").apply()
    }

    val isCalibrated: Boolean get() = calCount(true) > 0 && calCount(false) > 0

    // ---------- parent PIN ----------
    fun hasPin(): Boolean = sp.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("pin_salt", b64(salt))
            .putString("pin_hash", b64(hash(pin, salt)))
            .putInt("pin_fails", 0)
            .putLong("pin_lock_until", 0L)
            .apply()
    }

    /** Slow (PBKDF2) — call off the main thread. */
    fun checkPin(pin: String): PinResult {
        val now = System.currentTimeMillis()
        val lockUntil = sp.getLong("pin_lock_until", 0L)
        if (now < lockUntil) return PinResult.Locked(lockUntil - now)
        val salt = unb64(sp.getString("pin_salt", null)) ?: return PinResult.Wrong
        val stored = unb64(sp.getString("pin_hash", null)) ?: return PinResult.Wrong
        return if (MessageDigest.isEqual(stored, hash(pin, salt))) {
            sp.edit().putInt("pin_fails", 0).putLong("pin_lock_until", 0L).apply()
            PinResult.Ok
        } else {
            val fails = sp.getInt("pin_fails", 0) + 1
            val e = sp.edit().putInt("pin_fails", fails)
            if (fails >= 5) {
                // 30s, 60s, 120s ... capped at 15 minutes
                val wait = min(30_000L shl min(fails - 5, 5), 15 * 60_000L)
                e.putLong("pin_lock_until", now + wait)
                e.apply()
                PinResult.Locked(wait)
            } else {
                e.apply()
                PinResult.Wrong
            }
        }
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String?) = s?.let { Base64.decode(it, Base64.NO_WRAP) }
}
