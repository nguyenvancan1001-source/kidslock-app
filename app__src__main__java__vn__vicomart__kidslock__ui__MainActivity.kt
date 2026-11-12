package vn.vicomart.kidslock.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import vn.vicomart.kidslock.BuildConfig
import vn.vicomart.kidslock.Bus
import vn.vicomart.kidslock.Lang
import vn.vicomart.kidslock.LicenseManager
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R
import vn.vicomart.kidslock.detect.AgeEstimatorFactory
import vn.vicomart.kidslock.service.MonitorService
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var license: LicenseManager

    private lateinit var tvStatus: TextView
    private lateinit var tvLicense: TextView
    private lateinit var tvAi: TextView
    private lateinit var btnToggle: Button

    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        license = LicenseManager(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvLicense = findViewById(R.id.tvLicense)
        tvAi = findViewById(R.id.tvAi)
        btnToggle = findViewById(R.id.btnToggle)
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.main_version, BuildConfig.VERSION_NAME)

        btnToggle.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.btnPermCamera).setOnClickListener {
            cameraPerm.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.btnPermNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        findViewById<Button>(R.id.btnPermOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnPermBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            Dialogs.askPin(this) {
                SettingsActivity.authorizedUntil = System.currentTimeMillis() + 5 * 60_000L
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btnLanguage).setOnClickListener { chooseLanguage() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Bus.serviceTick.collect { refresh() }
            }
        }

        firstRunFlow()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        lifecycleScope.launch {
            license.refresh()
            refresh()
            autoStartIfNeeded()
        }
    }

    // ------------------------------------------------------------------ first run

    private fun firstRunFlow() {
        if (!prefs.consentAccepted) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.consent_title)
                .setMessage(R.string.consent_body)
                .setCancelable(false)
                .setPositiveButton(R.string.consent_accept) { _, _ ->
                    prefs.consentAccepted = true
                    firstRunFlow()
                }
                .setNegativeButton(R.string.consent_exit) { _, _ -> finish() }
                .show()
            return
        }
        if (!prefs.hasPin()) Dialogs.createPin(this, cancelable = false) { refresh() }
    }

    // ------------------------------------------------------------------ protection on/off

    private fun onToggle() {
        if (!prefs.consentAccepted || !prefs.hasPin()) {
            firstRunFlow(); return
        }
        if (prefs.protectionEnabled || MonitorService.isRunning) {
            Dialogs.askPin(this) {
                prefs.protectionEnabled = false
                MonitorService.send(this, MonitorService.ACTION_STOP)
                refresh()
            }
            return
        }
        when {
            !hasCamera() || !Settings.canDrawOverlays(this) ->
                Toast.makeText(this, R.string.toast_need_permissions, Toast.LENGTH_LONG).show()
            !license.isUsable() ->
                Toast.makeText(this, R.string.toast_license_invalid, Toast.LENGTH_LONG).show()
            else -> {
                prefs.protectionEnabled = true
                MonitorService.send(this, MonitorService.ACTION_START)
                if (!AgeEstimatorFactory.hasModel(this) && !prefs.isCalibrated) {
                    Toast.makeText(this, R.string.toast_need_calibration, Toast.LENGTH_LONG).show()
                }
                btnToggle.postDelayed({ refresh() }, 800)
            }
        }
    }

    /** Restarts protection after a reboot or a system kill, once the parent opens the app. */
    private fun autoStartIfNeeded() {
        if (prefs.protectionEnabled && !MonitorService.isRunning &&
            hasCamera() && Settings.canDrawOverlays(this) && license.isUsable()
        ) {
            MonitorService.send(this, MonitorService.ACTION_START)
            btnToggle.postDelayed({ refresh() }, 800)
        }
    }

    // ------------------------------------------------------------------ UI state

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBattery() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refresh() {
        if (!::tvStatus.isInitialized) return
        val running = MonitorService.isRunning
        val hasModel = AgeEstimatorFactory.hasModel(this)
        val ready = hasModel || prefs.isCalibrated

        tvStatus.text = getString(
            when {
                running && MonitorService.isLocked -> R.string.main_status_locked
                running && !ready -> R.string.main_status_standby
                running -> R.string.main_status_on
                else -> R.string.main_status_off
            }
        )
        btnToggle.text = getString(if (running || prefs.protectionEnabled) R.string.main_btn_disable else R.string.main_btn_enable)

        tvAi.text = when {
            hasModel -> getString(R.string.main_ai_model)
            prefs.isCalibrated -> getString(R.string.main_ai_calibrated)
            else -> getString(R.string.main_ai_needs_calibration)
        }

        val s = license.cached()
        val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
        tvLicense.text = when (s.status) {
            "dev" -> getString(R.string.main_license_unconfigured)
            "licensed" -> if (s.expiresAtMs == null) getString(R.string.main_license_lifetime, s.plan ?: "")
            else getString(R.string.main_license_licensed, s.plan ?: "", df.format(Date(s.expiresAtMs)))
            "trial" -> getString(R.string.main_license_trial, df.format(Date(s.trialEndsMs ?: 0L)))
            "expired" -> getString(R.string.main_license_expired)
            else -> getString(R.string.main_license_unknown)
        }

        setPermRow(R.id.tvPermCamera, R.id.btnPermCamera, R.string.perm_camera, hasCamera())
        setPermRow(R.id.tvPermNotif, R.id.btnPermNotif, R.string.perm_notifications, hasNotif())
        setPermRow(R.id.tvPermOverlay, R.id.btnPermOverlay, R.string.perm_overlay, Settings.canDrawOverlays(this))
        setPermRow(R.id.tvPermBattery, R.id.btnPermBattery, R.string.perm_battery, ignoresBattery())
    }

    private fun setPermRow(tvId: Int, btnId: Int, labelRes: Int, granted: Boolean) {
        val mark = if (granted) "✅ " else "⚠️ "
        findViewById<TextView>(tvId).text = mark + getString(labelRes)
        findViewById<Button>(btnId).visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "vi", "en")
        val labels = arrayOf(
            getString(R.string.lang_system), getString(R.string.lang_vi), getString(R.string.lang_en)
        )
        val current = tags.indexOf(Lang.current(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lang_title)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                Lang.apply(this, tags[which])
                // Recreate channels so notification category names follow the new language.
                vn.vicomart.kidslock.KidsLockApp.createChannels(this)
            }
            .show()
    }
}
