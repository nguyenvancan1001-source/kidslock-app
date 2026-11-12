package vn.vicomart.kidslock.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Runs a trained age model from assets/age_model.tflite, described by assets/age_model.json:
 * {
 *   "input_size": 224,          // square input
 *   "mean": 127.5, "std": 127.5,// float input = (pixel - mean) / std
 *   "bgr": false,               // channel order
 *   "output": "regression",     // "regression" (one age value) or "buckets" (class scores)
 *   "scale": 1.0,               // regression: age = output * scale
 *   "bucket_ages": [2,6,10,15,20,28,38,48,60,75], // buckets: representative age per class
 *   "crop_margin": 0.25         // extra border around the detected face
 * }
 */
class TfliteAgeEstimator private constructor(
    private val interpreter: Interpreter,
    private val cfg: Cfg
) : AgeEstimator {

    data class Cfg(
        val size: Int,
        val mean: Float,
        val std: Float,
        val bgr: Boolean,
        val output: String,
        val scale: Float,
        val buckets: FloatArray,
        val margin: Float
    )

    override val mode = "model"
    override fun isReady() = true

    private val inputIsFloat = interpreter.getInputTensor(0).dataType() == DataType.FLOAT32
    private val outputIsFloat = interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32
    private val outputSize = interpreter.getOutputTensor(0).shape().last()
    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(cfg.size * cfg.size * 3 * (if (inputIsFloat) 4 else 1))
        .order(ByteOrder.nativeOrder())
    private val pixels = IntArray(cfg.size * cfg.size)

    @Synchronized
    override fun estimate(frame: Bitmap, face: Face): AgeEstimate? {
        val box = face.boundingBox
        val mx = (box.width() * cfg.margin).toInt()
        val my = (box.height() * cfg.margin).toInt()
        val l = max(0, box.left - mx)
        val t = max(0, box.top - my)
        val r = min(frame.width, box.right + mx)
        val b = min(frame.height, box.bottom + my)
        if (r - l < 24 || b - t < 24) return null

        val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
        val scaled = Bitmap.createScaledBitmap(crop, cfg.size, cfg.size, true)
        scaled.getPixels(pixels, 0, cfg.size, 0, 0, cfg.size, cfg.size)
        if (crop !== frame) crop.recycle()
        if (scaled !== crop) scaled.recycle()

        input.rewind()
        for (p in pixels) {
            val r8 = (p shr 16) and 0xFF
            val g8 = (p shr 8) and 0xFF
            val b8 = p and 0xFF
            val c0 = if (cfg.bgr) b8 else r8
            val c2 = if (cfg.bgr) r8 else b8
            if (inputIsFloat) {
                input.putFloat((c0 - cfg.mean) / cfg.std)
                input.putFloat((g8 - cfg.mean) / cfg.std)
                input.putFloat((c2 - cfg.mean) / cfg.std)
            } else {
                input.put(c0.toByte()); input.put(g8.toByte()); input.put(c2.toByte())
            }
        }
        input.rewind()

        val values: FloatArray = if (outputIsFloat) {
            val out = Array(1) { FloatArray(outputSize) }
            interpreter.run(input, out)
            out[0]
        } else {
            val out = Array(1) { ByteArray(outputSize) }
            interpreter.run(input, out)
            FloatArray(outputSize) { (out[0][it].toInt() and 0xFF) / 255f }
        }

        return if (cfg.output == "regression" || outputSize == 1) {
            AgeEstimate(values[0] * cfg.scale, 0.7f)
        } else {
            if (cfg.buckets.size != outputSize) return null
            val probs = toProbabilities(values)
            var age = 0f
            var best = 0f
            for (i in probs.indices) {
                age += probs[i] * cfg.buckets[i]
                if (probs[i] > best) best = probs[i]
            }
            AgeEstimate(age, best)
        }
    }

    private fun toProbabilities(v: FloatArray): FloatArray {
        val sum = v.sum()
        val looksLikeProbs = v.all { it in 0f..1f } && sum in 0.98f..1.02f
        if (looksLikeProbs) return v
        val m = v.maxOrNull() ?: 0f
        val e = FloatArray(v.size) { exp(v[it] - m) }
        val s = e.sum()
        return FloatArray(v.size) { e[it] / s }
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "KidsLock"

        fun tryCreate(ctx: Context): TfliteAgeEstimator? {
            if (!AgeEstimatorFactory.hasModel(ctx)) return null
            return try {
                val fd = ctx.assets.openFd(AgeEstimatorFactory.MODEL_FILE)
                val model = FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
                fd.close()
                val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
                TfliteAgeEstimator(interpreter, readConfig(ctx))
            } catch (e: Exception) {
                Log.w(TAG, "Age model could not be loaded, using basic mode", e)
                null
            }
        }

        private fun readConfig(ctx: Context): Cfg {
            val o = try {
                JSONObject(ctx.assets.open(AgeEstimatorFactory.MODEL_CONFIG).bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                JSONObject()
            }
            val bucketsJson = o.optJSONArray("bucket_ages")
            val buckets = if (bucketsJson == null) FloatArray(0)
            else FloatArray(bucketsJson.length()) { bucketsJson.optDouble(it, 0.0).toFloat() }
            return Cfg(
                size = o.optInt("input_size", 224),
                mean = o.optDouble("mean", 127.5).toFloat(),
                std = o.optDouble("std", 127.5).toFloat().let { if (it == 0f) 1f else it },
                bgr = o.optBoolean("bgr", false),
                output = o.optString("output", "regression"),
                scale = o.optDouble("scale", 1.0).toFloat(),
                buckets = buckets,
                margin = o.optDouble("crop_margin", 0.25).toFloat()
            )
        }
    }
}
