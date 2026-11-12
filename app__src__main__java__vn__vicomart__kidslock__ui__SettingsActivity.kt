package vn.vicomart.kidslock.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import vn.vicomart.kidslock.Bus
import vn.vicomart.kidslock.LicenseManager
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R
import vn.vicomart.kidslock.detect.AgeEstimatorFactory
import vn.vicomart.kidslock.service.MonitorService

/** Parent settings — opened only after the PIN is entered in MainActivity. */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Set by MainActivity after a correct PIN; the screen refuses to open after this time. */
        @Volatile
        var authorizedUntil = 0L
    }

    private lateinit var prefs: Prefs
    private lateinit var license: LicenseManager
    private lateinit var tvResult: TextView
    private lateinit var tvCal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (System.currentTimeMillis() > authorizedUntil) {
            finish(); return
        }
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        license = LicenseManager(this)
        tvResult = findViewById(R.id.tvTestResult)
        tvCal = findViewById(R.id.tvCalStatus)

        val ages = listOf(6, 8, 10, 12, 14, 16)
        bindSpinner(R.id.spAge, ages.map { getString(R.string.set_age_item, it) },
            ages.indexOf(prefs.ageThreshold)) { prefs.ageThreshold = ages[it] }

        val sens = listOf(R.string.sens_low, R.string.sens_medium, R.string.sens_high)
        bindSpinner(R.id.spSensitivity, sens.map { getString(it) }, prefs.sensitivity) { prefs.sensitivity = it }

        val intervals = listOf(10, 15, 30, 60)
        bindSpinner(R.id.spInterval, intervals.map { getString(R.string.set_interval_item, it) },
            intervals.indexOf(prefs.intervalSec)) { prefs.intervalSec = intervals[it] }

        val graces = listOf(5, 10, 15, 30, 60)
        bindSpinner(R.id.spGrace, graces.map { getString(R.string.set_grace_item, it) },
            graces.indexOf(prefs.graceMin)) { prefs.graceMin = graces[it] }

        // ---- AI
        val hasModel = AgeEstimatorFactory.hasModel(this)
        findViewById<TextView>(R.id.tvAiMode).text =
            getString(if (hasModel) R.string.set_ai_mode_model else R.string.set_ai_mode_basic)
        val calViews = listOf(R.id.tvCalHint, R.id.btnCalAdult, R.id.btnCalChild, R.id.btnCalReset, R.id.tvCalStatus)
        calViews.forEach { findViewById<View>(it).visibility = if (hasModel) View.GONE else View.VISIBLE }

        findViewById<Button>(R.id.btnTest).setOnClickListener { sendToService(MonitorService.ACTION_TEST) }
        findViewById<Button>(R.id.btnCalAdult).setOnClickListener { sendToService(MonitorService.ACTION_CAL_ADULT) }
        findViewById<Button>(R.id.btnCalChild).setOnClickListener { sendToService(MonitorService.ACTION_CAL_CHILD) }
        findViewById<Button>(R.id.btnCalReset).setOnClickListener {
            prefs.clearCalibration()
            tvResult.text = getString(R.string.cal_reset_done)
            renderCal()
        }
        renderCal()

        // ---- License
        findViewById<TextView>(R.id.tvDeviceId).text = getString(R.string.set_device_id, license.deviceId)
        val etKey = findViewById<EditText>(R.id.etLicense)
        val btnActivate = findViewById<Button>(R.id.btnActivate)
        btnActivate.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.length < 8) {
                etKey.error = getString(R.string.lic_err_invalid_key); return@setOnClickListener
            }
            btnActivate.isEnabled = false
            lifecycleScope.launch {
                val s = license.activate(key)
                btnActivate.isEnabled = true
                val msg = when {
                    s.status == "dev" -> getString(R.string.main_license_unconfigured)
                    s.error == null && s.status == "licensed" -> getString(R.string.lic_ok)
                    else -> getString(errorText(s.error))
                }
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        // ---- Security
        findViewById<Button>(R.id.btnChangePin).setOnClickListener {
            Dialogs.createPin(this, cancelable = true) {}
        }

        Bus.testResult.value = null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Bus.testResult.collect { if (it != null) tvResult.text = it } }
                launch { Bus.serviceTick.collect { renderCal() } }
            }
        }
    }

    private fun sendToService(action: String) {
        if (!MonitorService.isRunning) {
            tvResult.text = getString(R.string.set_need_service); return
        }
        tvResult.text = getString(R.string.set_testing)
        MonitorService.send(this, action)
    }

    private fun renderCal() {
        tvCal.text = getString(R.string.cal_status, prefs.calCount(false), prefs.calCount(true))
    }

    private fun errorText(code: String?): Int = when (code) {
        "invalid_key" -> R.string.lic_err_invalid_key
        "revoked" -> R.string.lic_err_revoked
        "expired_key" -> R.string.lic_err_expired_key
        "device_limit" -> R.string.lic_err_device_limit
        "rate_limited" -> R.string.lic_err_rate_limited
        "network" -> R.string.lic_err_network
        else -> R.string.lic_err_unknown
    }

    private fun bindSpinner(id: Int, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        sp.setSelection(selected.coerceIn(0, labels.size - 1), false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}
