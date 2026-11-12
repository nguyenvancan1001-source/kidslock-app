package vn.vicomart.kidslock.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import vn.vicomart.kidslock.PinResult
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R
import java.util.concurrent.Executors

object Dialogs {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun pinField(a: Activity, hintRes: Int) = EditText(a).apply {
        hint = a.getString(hintRes)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        maxLines = 1
    }

    private fun container(a: Activity) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * a.resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
    }

    /** Create or change the parent PIN (4–8 digits, entered twice). */
    fun createPin(a: Activity, cancelable: Boolean, onDone: () -> Unit) {
        val box = container(a)
        val p1 = pinField(a, R.string.pin_hint)
        val p2 = pinField(a, R.string.pin_confirm_hint)
        box.addView(p1); box.addView(p2)
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pin_create_title)
            .setView(box)
            .setCancelable(cancelable)
            .setPositiveButton(R.string.ok, null)
            .apply { if (cancelable) setNegativeButton(R.string.cancel, null) }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val a1 = p1.text.toString()
                val a2 = p2.text.toString()
                when {
                    !a1.matches(Regex("^\\d{4,8}$")) -> p1.error = a.getString(R.string.pin_invalid)
                    a1 != a2 -> p2.error = a.getString(R.string.pin_mismatch)
                    else -> {
                        Prefs(a).setPin(a1)
                        Toast.makeText(a, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                        onDone()
                    }
                }
            }
        }
        dlg.show()
    }

    /** Asks for the parent PIN and runs [onOk] if it is correct. */
    fun askPin(a: Activity, onOk: () -> Unit) {
        val box = container(a)
        val p = pinField(a, R.string.pin_hint)
        box.addView(p)
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pin_enter_title)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            val btn = dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val pin = p.text.toString()
                if (pin.length < 4) {
                    p.error = a.getString(R.string.pin_invalid); return@setOnClickListener
                }
                btn.isEnabled = false
                worker.execute {
                    val r = Prefs(a).checkPin(pin)
                    main.post {
                        btn.isEnabled = true
                        if (a.isFinishing) return@post
                        when (r) {
                            is PinResult.Ok -> {
                                dlg.dismiss(); onOk()
                            }
                            is PinResult.Wrong -> {
                                p.text.clear(); p.error = a.getString(R.string.pin_wrong)
                            }
                            is PinResult.Locked -> {
                                p.text.clear()
                                p.error = a.getString(R.string.pin_locked, ((r.remainingMs + 999) / 1000).toInt())
                            }
                        }
                    }
                }
            }
        }
        dlg.show()
    }
}
