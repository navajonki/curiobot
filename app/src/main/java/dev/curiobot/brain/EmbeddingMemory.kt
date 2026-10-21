package dev.curiobot.brain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max

/**
 * Photo memories as embeddings only (never images), used to judge how new a
 * sight is. Keeps the most recent [capacity] entries.
 */
class EmbeddingMemory(private val file: File, private val dim: Int = 512, private val capacity: Int = 300) {
    private val items = ArrayDeque<FloatArray>()

    val size get() = items.size

    fun load() {
        items.clear()
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val n = inp.readInt()
                val d = inp.readInt()
                if (d != dim) return
                repeat(n) { items += FloatArray(d) { inp.readFloat() } }
            }
        }.onFailure { items.clear() }
    }

    fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(items.size)
            out.writeInt(dim)
            for (v in items) for (x in v) out.writeFloat(x)
        }
        tmp.renameTo(file)
    }

    /** 1 = unlike anything stored, 0 = identical to something stored. */
    fun novelty(e: FloatArray): Double {
        if (items.isEmpty()) return 1.0
        var best = -1.0
        for (v in items) {
            var dot = 0.0
            for (i in 0 until dim) dot += v[i] * e[i]
            best = max(best, dot)
        }
        // Measured with MobileCLIP-S0 on test photos: unrelated ~0.12, related
        // kinds (cat vs dog) 0.4-0.6, the same scene reframed ~0.98.
        return ((0.95 - best) / 0.7).coerceIn(0.0, 1.0)
    }

    fun add(e: FloatArray) {
        items.addLast(e.copyOf())
        while (items.size > capacity) items.removeFirst()
    }

    fun clear() {
        items.clear()
        file.delete()
    }
}
