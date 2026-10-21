package dev.curiobot.text

import android.content.Context
import dev.curiobot.log.Telemetry
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * The text model is too big to ship inside the app, so it downloads once on
 * first launch (resumable), then everything runs offline.
 */
object ModelStore {
    const val URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
    const val SIZE = 347_251_840L

    fun file(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, "qwen3_0.6b_nothink.litertlm")

    fun isReady(ctx: Context) = file(ctx).length() == SIZE

    /** Blocking; call off the main thread. [progress] gets 0..1. */
    fun download(ctx: Context, progress: (Float) -> Unit) {
        val dest = file(ctx)
        val part = File(dest.parentFile, dest.name + ".part")
        val free = dest.parentFile!!.usableSpace
        if (free < SIZE - part.length() + 50_000_000L) {
            throw IllegalStateException("Not enough space: ${free / 1_048_576} MB free")
        }
        val start = part.length()
        val t0 = System.currentTimeMillis()
        Telemetry.log("model_download_start", JSONObject().put("resume_from", start).put("free_mb", free / 1_048_576))
        var conn = URL(URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
        var code = conn.responseCode
        // Hugging Face redirects to a CDN on another host; follow manually if needed.
        var hops = 0
        while (code in 301..308 && hops < 5) {
            val next = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(URL(URL), next).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
            code = conn.responseCode
            hops++
        }
        if (code !in 200..299) throw IllegalStateException("Download failed: HTTP $code")
        val resumed = code == 206
        var written = if (resumed) start else 0L
        var lastPct = -1
        RandomAccessFile(part, "rw").use { out ->
            if (!resumed) out.setLength(0)
            out.seek(written)
            conn.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    progress(written.toFloat() / SIZE)
                    val pct = (written * 100 / SIZE).toInt()
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct
                        Telemetry.log("model_download_progress", JSONObject().put("pct", pct)
                            .put("mb", written / 1_048_576))
                    }
                }
            }
        }
        conn.disconnect()
        if (part.length() != SIZE) throw IllegalStateException("Download incomplete: ${part.length()} of $SIZE bytes")
        if (!part.renameTo(dest)) throw IllegalStateException("Could not move model into place")
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        Telemetry.log("model_download_done", JSONObject().put("seconds", secs)
            .put("mb_per_s", Math.round((SIZE - start) / 1_048_576.0 / secs * 10) / 10.0))
    }
}
