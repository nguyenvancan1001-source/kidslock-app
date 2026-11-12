package vn.vicomart.kidslock.detect

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * CameraX analyzer: finds the main face in each frame, estimates the age and reports a [FaceSample].
 * Runs on the analysis executor; [onSample] is called on that thread.
 */
class FaceAgeAnalyzer(
    private val detector: FaceDetector,
    private val estimator: AgeEstimator,
    private val skipFrames: Int,
    private val onSample: (FaceSample) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var stopped = false
    private var frames = 0

    override fun analyze(image: ImageProxy) {
        if (stopped) {
            image.close(); return
        }
        frames++
        // First frames are often dark while auto-exposure settles.
        if (frames <= skipFrames) {
            image.close(); return
        }

        val bitmap: Bitmap = try {
            val raw = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) raw else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame conversion failed", e)
            return
        } finally {
            image.close()
        }

        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 2, TimeUnit.SECONDS)
            val face = faces
                .filter { abs(it.headEulerAngleY) < 30f && abs(it.headEulerAngleZ) < 25f }
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return
            // Ignore small faces in the background — only the person holding the phone counts.
            if (face.boundingBox.width() < bitmap.width * 0.15f) return
            if (stopped) return

            val features = FaceFeatures.extract(face)
            val est = if (estimator.isReady()) estimator.estimate(bitmap, face) else null
            onSample(FaceSample(est?.age, est?.confidence ?: 0f, features))
        } catch (e: Exception) {
            Log.w(TAG, "Face analysis failed", e)
        }
    }

    companion object {
        private const val TAG = "KidsLock"
    }
}
