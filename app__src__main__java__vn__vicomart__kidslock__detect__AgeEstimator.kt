package vn.vicomart.kidslock.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import vn.vicomart.kidslock.Prefs
import kotlin.math.abs
import kotlin.math.sqrt

data class AgeEstimate(val age: Float, val confidence: Float)

/** One analysed camera frame with a usable face. */
data class FaceSample(val age: Float?, val confidence: Float, val features: FloatArray?)

interface AgeEstimator {
    /** "model" = trained age model in assets, "basic" = calibrated face-proportion check. */
    val mode: String

    /** False while the estimator cannot judge age yet (basic mode before calibration). */
    fun isReady(): Boolean

    /** [frame] is upright; [face] coordinates are in [frame]. Called on a background thread. */
    fun estimate(frame: Bitmap, face: Face): AgeEstimate?

    fun close() {}
}

object AgeEstimatorFactory {
    const val MODEL_FILE = "age_model.tflite"
    const val MODEL_CONFIG = "age_model.json"

    fun hasModel(ctx: Context): Boolean =
        try {
            ctx.assets.list("")?.contains(MODEL_FILE) == true
        } catch (_: Exception) {
            false
        }

    fun create(ctx: Context): AgeEstimator =
        TfliteAgeEstimator.tryCreate(ctx) ?: HeuristicAgeEstimator(Prefs(ctx))
}

/** Face-proportion features from ML Kit contours: eye height, lower-face length, eye spacing. */
object FaceFeatures {
    fun extract(face: Face): FloatArray? {
        val outline = face.getContour(FaceContour.FACE)?.points ?: return null
        val le = face.getContour(FaceContour.LEFT_EYE)?.points ?: return null
        val re = face.getContour(FaceContour.RIGHT_EYE)?.points ?: return null
        val nose = face.getContour(FaceContour.NOSE_BOTTOM)?.points ?: return null
        if (outline.size < 10 || le.isEmpty() || re.isEmpty() || nose.isEmpty()) return null

        val top = outline.minOf { it.y }
        val bottom = outline.maxOf { it.y }
        val left = outline.minOf { it.x }
        val right = outline.maxOf { it.x }
        val h = bottom - top
        val w = right - left
        if (h <= 1f || w <= 1f) return null

        val eyeY = (avgY(le) + avgY(re)) / 2f
        val noseY = avgY(nose)
        val eyeDist = abs(avgX(le) - avgX(re))
        return floatArrayOf((eyeY - top) / h, (bottom - noseY) / h, eyeDist / w)
    }

    private fun avgY(p: List<PointF>) = p.sumOf { it.y.toDouble() }.toFloat() / p.size
    private fun avgX(p: List<PointF>) = p.sumOf { it.x.toDouble() }.toFloat() / p.size
}

/**
 * Basic mode (no trained model): compares the face proportions with the family's own
 * calibration samples (adults vs children) and picks the nearer group.
 * Without calibration it reports "not ready" and never locks.
 */
class HeuristicAgeEstimator(private val prefs: Prefs) : AgeEstimator {
    override val mode = "basic"

    override fun isReady(): Boolean = prefs.isCalibrated

    override fun estimate(frame: Bitmap, face: Face): AgeEstimate? {
        val f = FaceFeatures.extract(face) ?: return null
        val adult = prefs.calCentroid(false) ?: return null
        val child = prefs.calCentroid(true) ?: return null
        if (adult.size != f.size || child.size != f.size) return null
        val dA = dist(f, adult)
        val dC = dist(f, child)
        val margin = abs(dA - dC) / (dA + dC + 1e-6f)
        val threshold = prefs.ageThreshold.toFloat()
        // Map the group decision onto an age so the same verdict logic works for both modes.
        val age = if (dC < dA) threshold - 4f else threshold + 15f
        return AgeEstimate(age.coerceAtLeast(1f), (0.5f + margin / 2f).coerceIn(0f, 1f))
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return sqrt(s)
    }
}
