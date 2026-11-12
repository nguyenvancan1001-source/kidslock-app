package vn.vicomart.kidslock.service

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import vn.vicomart.kidslock.Bus
import vn.vicomart.kidslock.KidsLockApp
import vn.vicomart.kidslock.Lang
import vn.vicomart.kidslock.LicenseManager
import vn.vicomart.kidslock.Prefs
import vn.vicomart.kidslock.R
import vn.vicomart.kidslock.detect.AgeEstimator
import vn.vicomart.kidslock.detect.AgeEstimatorFactory
import vn.vicomart.kidslock.detect.FaceAgeAnalyzer
import vn.vicomart.kidslock.detect.FaceSample
import vn.vicomart.kidslock.detect.Verdict
import vn.vicomart.kidslock.detect.VerdictRules
import vn.vicomart.kidslock.lock.LockOverlay
import vn.vicomart.kidslock.ui.BiometricUnlockActivity
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that samples the front camera in short bursts while the screen is on,
 * estimates the user's age on-device and shows the lock screen when a child is detected.
 * Camera frames never leave the device and are not stored.
 */
class MonitorService : LifecycleService(), LockOverlay.Listener {

    companion object {
        const val ACTION_START = "vn.vicomart.kidslock.START"
        const val ACTION_STOP = "vn.vicomart.kidslock.STOP"
        const val ACTION_UNLOCKED = "vn.vicomart.kidslock.UNLOCKED"
        const val ACTION_RELOCK = "vn.vicomart.kidslock.RELOCK"
        const val ACTION_TEST = "vn.vicomart.kidslock.TEST"
        const val ACTION_CAL_ADULT = "vn.vicomart.kidslock.CAL_ADULT"
        const val ACTION_CAL_CHILD = "vn.vicomart.kidslock.CAL_CHILD"

        private const val TAG = "KidsLock"
        private const val NOTIF_ID = 1001
        private const val LICENSE_REFRESH_MS = 6L * 3600 * 1000

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isLocked = false
            private set

        /** Only START may launch the service; other actions are ignored when it is not running. */
        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, MonitorService::class.java).setAction(action)
            try {
                if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i)
                else if (isRunning) ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot deliver $action", e)
            }
        }
    }

    private enum class Mode { DETECT, TEST, CAL_ADULT, CAL_CHILD }

    private lateinit var prefs: Prefs
    private lateinit var license: LicenseManager
    private lateinit var overlay: LockOverlay
    private lateinit var detector: FaceDetector
    private lateinit var estimator: AgeEstimator
    private lateinit var analysisExecutor: ExecutorService

    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeAnalyzer: FaceAgeAnalyzer? = null
    private var sampling = false
    private var burstId = 0
    private var screenOn = true
    private var childStreak = 0
    private var biometricPending = false
    private var started = false

    private val checkRunnable = Runnable { runCheck(Mode.DETECT) }
    private val biometricTimeout = Runnable { if (biometricPending) relock() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    handler.removeCallbacks(checkRunnable)
                    abortBurst()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    scheduleCheck(1500)
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    scheduleCheck(600)
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        license = LicenseManager(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // e.g. restarted by the system from the background on Android 14+, where camera
            // services may only start while the app is open.
            Log.w(TAG, "Cannot start camera service", e)
            KidsLockApp.notifyAlert(this, R.string.alert_service_title, R.string.alert_service_text)
            stopSelf(); return
        }

        started = true
        isRunning = true
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build()
        )
        estimator = AgeEstimatorFactory.create(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        overlay = LockOverlay(this, prefs, this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOn = getSystemService(PowerManager::class.java).isInteractive

        // Restore the lock screen if the service was restarted while locked.
        if (prefs.locked) lock() else scheduleCheck(1500)

        lifecycleScope.launch {
            while (isActive) {
                license.refresh()
                if (!license.isUsable() && !isLocked) {
                    onLicenseInvalid()
                    break
                }
                delay(LICENSE_REFRESH_MS)
            }
        }
        Bus.tick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!started) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.locked = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UNLOCKED -> onUnlocked()
            ACTION_RELOCK -> relock()
            ACTION_TEST -> runCheck(Mode.TEST)
            ACTION_CAL_ADULT -> runCheck(Mode.CAL_ADULT)
            ACTION_CAL_CHILD -> runCheck(Mode.CAL_CHILD)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isLocked = false
        handler.removeCallbacksAndMessages(null)
        if (started) {
            abortBurst()
            overlay.release()
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            detector.close()
            estimator.close()
            analysisExecutor.shutdown()
        }
        Bus.tick()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ detection

    private fun scheduleCheck(delayMs: Long) {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, delayMs)
    }

    private fun runCheck(mode: Mode) {
        if (sampling) {
            if (mode == Mode.DETECT) return
            abortBurst() // a test/calibration request takes priority
        }
        if (mode == Mode.DETECT) {
            if (!screenOn || isLocked) return
            val now = System.currentTimeMillis()
            if (now < prefs.unlockedUntil) {
                scheduleCheck(prefs.unlockedUntil - now + 500)
                return
            }
            if (!license.isUsable()) {
                onLicenseInvalid(); return
            }
            if (!estimator.isReady()) return // basic mode not calibrated yet: standby
        }
        startBurst(mode)
    }

    private fun startBurst(mode: Mode) {
        sampling = true
        val id = ++burstId
        val wanted = if (mode == Mode.DETECT) 4 else 8
        val timeoutMs = if (mode == Mode.DETECT) 3500L else 7000L
        val samples = Collections.synchronizedList(mutableListOf<FaceSample>())
        val finished = AtomicBoolean(false)

        lateinit var timeout: Runnable
        val finish = {
            if (finished.compareAndSet(false, true)) {
                handler.post {
                    handler.removeCallbacks(timeout)
                    if (id == burstId) {
                        stopCamera()
                        sampling = false
                        onBurstDone(mode, samples.toList())
                    }
                }
            }
        }
        timeout = Runnable { finish() }

        val analyzer = FaceAgeAnalyzer(detector, estimator, skipFrames = 4) { s ->
            samples.add(s)
            if (samples.size >= wanted) finish()
        }
        activeAnalyzer = analyzer
        handler.postDelayed(timeout, timeoutMs)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (id != burstId || !sampling) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor, analyzer)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.w(TAG, "Camera bind failed", e)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Cancels a running burst without evaluating it. */
    private fun abortBurst() {
        if (!sampling) return
        burstId++
        sampling = false
        stopCamera()
    }

    private fun stopCamera() {
        activeAnalyzer?.stopped = true
        activeAnalyzer = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
    }

    private fun onBurstDone(mode: Mode, samples: List<FaceSample>) {
        val c = Lang.wrap(this)
        when (mode) {
            Mode.DETECT -> {
                when (VerdictRules.decide(samples, prefs.ageThreshold)) {
                    Verdict.CHILD -> {
                        childStreak++
                        if (childStreak >= VerdictRules.requiredStreak(prefs.sensitivity)) {
                            childStreak = 0
                            lock()
                        } else {
                            scheduleCheck(1500) // confirm quickly
                        }
                    }
                    Verdict.ADULT, Verdict.NONE -> {
                        childStreak = 0
                        scheduleCheck(prefs.intervalSec * 1000L)
                    }
                }
            }
            Mode.TEST -> {
                val ages = samples.mapNotNull { it.age }
                Bus.testResult.value = when {
                    samples.isEmpty() -> c.getString(R.string.test_no_face)
                    !estimator.isReady() || ages.isEmpty() -> c.getString(R.string.test_not_ready)
                    else -> {
                        val med = VerdictRules.median(ages)
                        val who = if (med < prefs.ageThreshold) c.getString(R.string.result_child)
                        else c.getString(R.string.result_adult)
                        val modeName = if (estimator.mode == "model") c.getString(R.string.set_ai_mode_model)
                        else c.getString(R.string.set_ai_mode_basic)
                        c.getString(R.string.test_result, med, ages.size, who, modeName)
                    }
                }
                resumeDetection()
            }
            Mode.CAL_ADULT, Mode.CAL_CHILD -> {
                val feats = samples.mapNotNull { it.features }
                if (feats.size < 4) {
                    Bus.testResult.value = c.getString(R.string.cal_failed)
                } else {
                    val n = feats[0].size
                    val mean = FloatArray(n) { i -> feats.map { it[i] }.average().toFloat() }
                    prefs.addCalibration(mode == Mode.CAL_CHILD, mean)
                    Bus.testResult.value = c.getString(
                        R.string.cal_saved, prefs.calCount(false), prefs.calCount(true)
                    )
                }
                Bus.tick()
                resumeDetection()
            }
        }
    }

    private fun resumeDetection() {
        if (!isLocked) scheduleCheck(prefs.intervalSec * 1000L)
    }

    // ------------------------------------------------------------------ lock / unlock

    private fun lock() {
        isLocked = true
        prefs.locked = true
        handler.removeCallbacks(checkRunnable)
        if (!overlay.show()) {
            Log.w(TAG, "Overlay permission missing — cannot lock")
            isLocked = false
            prefs.locked = false
            KidsLockApp.notifyAlert(this, R.string.alert_service_title, R.string.alert_overlay_text)
            return
        }
        updateNotification()
        Bus.tick()
    }

    private fun relock() {
        biometricPending = false
        handler.removeCallbacks(biometricTimeout)
        if (isLocked) overlay.show()
    }

    private fun onUnlocked() {
        biometricPending = false
        handler.removeCallbacks(biometricTimeout)
        isLocked = false
        prefs.locked = false
        childStreak = 0
        overlay.hide()
        prefs.unlockedUntil = System.currentTimeMillis() + prefs.graceMin * 60_000L
        scheduleCheck(prefs.graceMin * 60_000L + 500)
        updateNotification()
        Bus.tick()
    }

    override fun onPinCorrect() = onUnlocked()

    override fun onBiometricRequested() {
        biometricPending = true
        overlay.hide()
        handler.postDelayed(biometricTimeout, 60_000)
        val i = Intent(this, BiometricUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open biometric prompt", e)
            relock()
        }
    }

    private fun onLicenseInvalid() {
        KidsLockApp.notifyAlert(this, R.string.alert_license_title, R.string.alert_license_text)
        prefs.locked = false
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val c = Lang.wrap(this)
        val text = when {
            isLocked -> c.getString(R.string.notif_locked_text)
            ::estimator.isInitialized && !estimator.isReady() -> c.getString(R.string.notif_standby_text)
            else -> c.getString(R.string.notif_protecting_text)
        }
        return NotificationCompat.Builder(this, KidsLockApp.CH_PROTECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(c.getString(R.string.notif_protecting))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(KidsLockApp.openAppIntent(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
        }
    }
}
