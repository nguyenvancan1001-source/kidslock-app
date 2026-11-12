package vn.vicomart.kidslock.lock

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.biometric.BiometricManager
import vn.vicomart.kidslock.Lang
import vn.vicomart.kidslock.PinResult
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R
import java.util.concurrent.Executors

/** Full-screen lock drawn over every app. Unlocks with the parent PIN or the owner's biometrics. */
class LockOverlay(
    private val service: Context,
    private val prefs: Prefs,
    private val listener: Listener
) {
    interface Listener {
        fun onPinCorrect()
        fun onBiometricRequested()
    }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var view: View? = null
    private val pin = StringBuilder()
    private var checking = false

    val isShowing: Boolean get() = view != null

    /** Returns false if the "display over other apps" permission is missing. */
    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(service)) return false
        val ctx = ContextThemeWrapper(Lang.wrap(service), R.style.Theme_KidsLock_Overlay)
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_lock, null)
        bind(v, ctx)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        return try {
            wm.addView(v, lp)
            view = v
            v.isFocusableInTouchMode = true
            v.requestFocus()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        view?.let {
            try {
                wm.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        view = null
        pin.clear()
        checking = false
    }

    fun release() {
        hide()
        worker.shutdownNow()
    }

    private fun bind(v: View, ctx: Context) {
        val dots = v.findViewById<TextView>(R.id.tvPinDots)
        val error = v.findViewById<TextView>(R.id.tvLockError)

        fun render() {
            dots.text = if (pin.isEmpty()) ctx.getString(R.string.lock_enter_pin) else "●".repeat(pin.length)
        }
        render()

        val digits = listOf(
            R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4,
            R.id.k5, R.id.k6, R.id.k7, R.id.k8, R.id.k9
        )
        digits.forEachIndexed { d, id ->
            v.findViewById<Button>(id).setOnClickListener {
                if (pin.length < 8 && !checking) {
                    pin.append(d); error.text = ""; render()
                }
            }
        }
        v.findViewById<Button>(R.id.kDel).setOnClickListener {
            if (pin.isNotEmpty() && !checking) {
                pin.deleteCharAt(pin.length - 1); render()
            }
        }
        v.findViewById<Button>(R.id.kOk).setOnClickListener {
            if (pin.length < 4 || checking) return@setOnClickListener
            checking = true
            val attempt = pin.toString()
            worker.execute {
                val result = prefs.checkPin(attempt)
                main.post {
                    checking = false
                    if (view == null) return@post
                    when (result) {
                        is PinResult.Ok -> listener.onPinCorrect()
                        is PinResult.Wrong -> {
                            pin.clear(); render()
                            error.text = ctx.getString(R.string.lock_wrong_pin)
                        }
                        is PinResult.Locked -> {
                            pin.clear(); render()
                            error.text = ctx.getString(
                                R.string.lock_try_later, ((result.remainingMs + 999) / 1000).toInt()
                            )
                        }
                    }
                }
            }
        }

        val bio = v.findViewById<Button>(R.id.btnBiometric)
        val canBio = BiometricManager.from(service)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        bio.visibility = if (canBio) View.VISIBLE else View.GONE
        bio.setOnClickListener { listener.onBiometricRequested() }

        // Swallow the Back key so the lock cannot be dismissed.
        v.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK || (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ESCAPE)
        }
    }
}
