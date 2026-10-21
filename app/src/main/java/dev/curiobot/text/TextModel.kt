package dev.curiobot.text

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dev.curiobot.brain.Prompt
import dev.curiobot.log.Prefs
import dev.curiobot.log.Telemetry
import org.json.JSONObject

/**
 * The small text model (Qwen3-0.6B, 4-bit, no-think build) that writes the
 * creature's on-screen lines. Text only: there is no audio anywhere in the app.
 *
 * Not thread-safe: call from one thread (the game uses a single-thread dispatcher).
 */
class TextModel(private val modelPath: String, private val cacheDir: String) {
    var backendName: String = "none"; private set
    private var engine: Engine? = null

    val ready get() = engine != null

    /**
     * Try the GPU first (much faster prompt processing), then fall back to CPU.
     *
     * A GPU driver can crash natively, which no catch block survives. So a flag is
     * set before the GPU attempt and cleared after; if it's still set at the next
     * launch, the last attempt killed the app and the GPU is skipped from then on.
     */
    fun init(preference: String) {
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        if (Prefs.gpuAttemptInFlight) {
            Prefs.gpuAttemptInFlight = false
            Prefs.gpuBroken = true
            Telemetry.warn("gpu_crash_suspected", JSONObject().put("note", "previous GPU init never finished; using CPU"))
        }
        val order = when {
            preference == "cpu" -> listOf("cpu")
            preference == "gpu" -> listOf("gpu", "cpu")
            Prefs.gpuBroken -> listOf("cpu")
            else -> listOf("gpu", "cpu")
        }
        for (name in order) {
            val t = System.currentTimeMillis()
            Telemetry.log("text_model_init_start", JSONObject().put("backend", name))
            if (name == "gpu") Prefs.gpuAttemptInFlight = true
            try {
                val backend = if (name == "gpu") Backend.GPU() else Backend.CPU(4, null)
                val e = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir))
                e.initialize()
                engine = e
                backendName = name
                Telemetry.log("text_model_init_done", JSONObject().put("backend", name)
                    .put("ms", System.currentTimeMillis() - t))
                return
            } catch (e: Throwable) {
                Telemetry.error("text_model_init_failed", e, JSONObject().put("backend", name)
                    .put("ms", System.currentTimeMillis() - t))
            } finally {
                if (name == "gpu") Prefs.gpuAttemptInFlight = false
            }
        }
    }

    data class Output(val raw: String, val ms: Long)

    fun write(spec: Prompt.Spec, temperature: Double, seed: Int): Output {
        val e = engine ?: error("text model not ready")
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(spec.system),
            initialMessages = spec.examples.flatMap { (u, a) -> listOf(Message.user(u), Message.model(a)) },
            samplerConfig = SamplerConfig(40, 0.9, temperature, seed),
            maxOutputToken = 80,
        )
        val t = System.currentTimeMillis()
        val raw = e.createConversation(cfg).use { conv ->
            val reply = conv.sendMessage(spec.user)
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
        return Output(raw.trim(), System.currentTimeMillis() - t)
    }

    fun close() {
        engine?.close()
        engine = null
    }
}
