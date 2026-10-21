package dev.curiobot.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import dev.curiobot.brain.Verdict
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The creature's eye: MobileCLIP-S0's image encoder plus a fixed vocabulary.
 *
 * A photo can only ever come out as one of the vocabulary rows, so nothing
 * written in a photo can reach the text model. Matching mirrors
 * tools/vocab/test_vision.py, which is where the thresholds were tuned.
 */
class Eye(ctx: Context) {
    data class Row(val label: String, val kind: String, val cat: String)

    data class Perception(
        val verdict: Verdict,
        val label: String,
        val cat: String,
        val confidence: Double,
        val top: List<Pair<String, Double>>,
        val tripGroup: String,
        val tripScore: Double,
        val embedding: FloatArray,
        val msPrep: Long,
        val msModel: Long,
        val msMatch: Long,
    ) {
        fun toLog(): JSONObject = JSONObject().apply {
            put("verdict", verdict.name.lowercase())
            put("label", label)
            put("cat", cat)
            put("confidence", r3(confidence))
            put("top", JSONObject().also { o -> top.forEach { (l, p) -> o.put(l, r3(p)) } })
            put("trip_group", tripGroup)
            put("trip_score", r3(tripScore))
            put("ms_prep", msPrep)
            put("ms_model", msModel)
            put("ms_match", msMatch)
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val rows: List<Row>
    private val dim: Int
    private val matrix: FloatArray
    private val logitScale: Float
    private val size: Int
    private val tripMin: Double
    private val unsureBelow: Double

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(ctx.assets.open("mobileclip_s0_vision.onnx").use { it.readBytes() }, opts)
        val meta = JSONObject(ctx.assets.open("vocab.json").bufferedReader().use { it.readText() })
        val arr = meta.getJSONArray("rows")
        rows = List(arr.length()) { i ->
            val r = arr.getJSONObject(i)
            Row(r.getString("label"), r.getString("kind"), r.getString("cat"))
        }
        dim = meta.getInt("dim")
        val params = meta.getJSONObject("params")
        logitScale = params.getDouble("logit_scale").toFloat()
        size = params.getInt("image_size")
        tripMin = params.getDouble("tripwire_group_min")
        unsureBelow = params.getDouble("unsure_below")
        val bytes = ctx.assets.open("vocab_emb.bin").use { it.readBytes() }
        matrix = FloatArray(rows.size * dim)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(matrix)
    }

    val vocabularySize get() = rows.count { it.kind == "normal" }

    fun see(photo: Bitmap): Perception {
        val t0 = System.nanoTime()
        val input = preprocess(photo)
        val t1 = System.nanoTime()
        val emb = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { x ->
            session.run(mapOf("pixel_values" to x)).use { out ->
                @Suppress("UNCHECKED_CAST")
                (out[0].value as Array<FloatArray>)[0]
            }
        }
        val t2 = System.nanoTime()
        normalize(emb)
        val p = match(emb)
        val t3 = System.nanoTime()
        return p.copy(embedding = emb, msPrep = ms(t0, t1), msModel = ms(t1, t2), msMatch = ms(t2, t3))
    }

    /** Center square crop, resize to 256, scale to 0..1, CHW. No mean/std (MobileCLIP). */
    private fun preprocess(src: Bitmap): FloatBuffer {
        val side = min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val img = Bitmap.createScaledBitmap(sq, size, size, true)
        val px = IntArray(size * size)
        img.getPixels(px, 0, size, 0, 0, size, size)
        if (img !== src && img !== sq) img.recycle()
        if (sq !== src) sq.recycle()
        val plane = size * size
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            buf.put(i, ((c shr 16) and 0xFF) / 255f)
            buf.put(plane + i, ((c shr 8) and 0xFF) / 255f)
            buf.put(2 * plane + i, (c and 0xFF) / 255f)
        }
        return buf
    }

    private fun match(e: FloatArray): Perception {
        val n = rows.size
        val logits = FloatArray(n)
        var maxL = Float.NEGATIVE_INFINITY
        var topRow = 0
        for (r in 0 until n) {
            var dot = 0f
            val off = r * dim
            for (k in 0 until dim) dot += matrix[off + k] * e[k]
            val l = logitScale * dot
            logits[r] = l
            if (l > maxL) { maxL = l; topRow = r }
        }
        var sum = 0.0
        val probs = DoubleArray(n) { r -> exp((logits[r] - maxL).toDouble()).also { sum += it } }
        val byLabel = LinkedHashMap<Pair<String, String>, Double>()
        val trip = LinkedHashMap<String, Double>()
        for (r in 0 until n) {
            val p = probs[r] / sum
            val row = rows[r]
            if (row.kind == "tripwire") trip[row.label] = (trip[row.label] ?: 0.0) + p
            else byLabel[row.label to row.kind] = (byLabel[row.label to row.kind] ?: 0.0) + p
        }
        val ranked = byLabel.entries.sortedByDescending { it.value }
        val (best, conf) = ranked.first().let { it.key to it.value }
        val worst = trip.maxByOrNull { it.value }
        val catOf = rows.firstOrNull { it.label == best.first && it.kind == best.second }?.cat ?: ""
        val verdict = when {
            rows[topRow].kind == "tripwire" || (worst?.value ?: 0.0) >= tripMin -> Verdict.TRIPWIRE
            best.second == "lowinfo" -> Verdict.LOWINFO
            conf < unsureBelow -> Verdict.UNSURE
            best.second == "person" -> Verdict.PERSON
            else -> Verdict.NORMAL
        }
        return Perception(
            verdict = verdict,
            label = best.first,
            cat = catOf,
            confidence = conf,
            top = ranked.take(10).map { it.key.first to it.value },
            tripGroup = worst?.key ?: "",
            tripScore = worst?.value ?: 0.0,
            embedding = e,
            msPrep = 0, msModel = 0, msMatch = 0,
        )
    }

    fun close() = session.close()

    private fun normalize(v: FloatArray) {
        var s = 0.0
        for (x in v) s += x * x
        val inv = (1.0 / sqrt(s)).toFloat()
        for (i in v.indices) v[i] *= inv
    }

    companion object {
        private fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        private fun r3(d: Double) = Math.round(d * 1000) / 1000.0
    }
}
