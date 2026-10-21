package dev.curiobot.brain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.SplittableRandom
import kotlin.math.max

/**
 * One real object the creature has seen: its word, a few image fingerprints
 * (embeddings, never photos), and what it has learned about it. Teaching
 * corrects [word]; seeing it again and again earns it a [nickname].
 */
class Thing(
    val id: Int,
    var word: String,
    var cat: String,
    var nickname: String?,
    var taught: Boolean,
    var count: Int,
    val firstSeen: Long,
    var lastSeen: Long,
    val embeddings: MutableList<FloatArray>,
)

/**
 * The creature's memory of objects. It drives recognition ("that's my mug"),
 * novelty (how unlike anything it knows a photo is), teaching and nicknames.
 * Never holds anything from photos of people.
 */
class ThingMemory(private val file: File, private val dim: Int = 512, private val capacity: Int = 300) {
    val things = mutableListOf<Thing>()
    private var nextId = 1

    data class Match(val thing: Thing, val similarity: Double)

    fun best(e: FloatArray): Match? {
        var bestThing: Thing? = null
        var bestSim = -2.0
        for (t in things) for (v in t.embeddings) {
            val s = dot(v, e)
            if (s > bestSim) { bestSim = s; bestThing = t }
        }
        return bestThing?.let { Match(it, bestSim) }
    }

    /** The same object, re-shot, typically scores 0.9+; related kinds of things top out near 0.65. */
    fun recognize(e: FloatArray): Match? = best(e)?.takeIf { it.similarity >= RECOGNIZE }

    /** 1 = unlike anything it knows, 0 = basically the same photo. */
    fun novelty(e: FloatArray): Double {
        val b = best(e) ?: return 1.0
        return ((0.95 - b.similarity) / 0.7).coerceIn(0.0, 1.0)
    }

    fun add(word: String, cat: String, e: FloatArray, now: Long, taught: Boolean = false): Thing {
        val t = Thing(nextId++, word, cat, null, taught, 1, now, now, mutableListOf(e.copyOf()))
        things += t
        prune()
        return t
    }

    /** Seen again: count it, and keep the new angle if it's different enough. */
    fun observe(t: Thing, e: FloatArray, now: Long) {
        t.count++
        t.lastSeen = now
        val closest = t.embeddings.maxOf { dot(it, e) }
        if (closest < 0.97) {
            t.embeddings += e.copyOf()
            while (t.embeddings.size > MAX_ANGLES) t.embeddings.removeAt(1) // keep the first sighting
        }
    }

    /** The person taught it what this really is. */
    fun relabel(t: Thing, word: String, cat: String) {
        if (t.word != word) t.nickname = null
        t.word = word
        t.cat = cat
        t.taught = true
    }

    fun byId(id: Int) = things.firstOrNull { it.id == id }

    val size get() = things.size

    private fun prune() {
        while (things.size > capacity) {
            val victim = things.filter { !it.taught && it.nickname == null }.minByOrNull { it.lastSeen }
                ?: things.minByOrNull { it.lastSeen }!!
            things.remove(victim)
        }
    }

    fun load() {
        things.clear()
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                check(inp.readInt() == MAGIC)
                val d = inp.readInt()
                if (d != dim) return
                nextId = inp.readInt()
                repeat(inp.readInt()) {
                    val id = inp.readInt()
                    val word = inp.readUTF()
                    val cat = inp.readUTF()
                    val nick = inp.readUTF().ifEmpty { null }
                    val taught = inp.readBoolean()
                    val count = inp.readInt()
                    val first = inp.readLong()
                    val last = inp.readLong()
                    val embs = MutableList(inp.readInt()) { FloatArray(d) { inp.readFloat() } }
                    things += Thing(id, word, cat, nick, taught, count, first, last, embs)
                }
            }
        }.onFailure { things.clear() }
    }

    fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(dim)
            out.writeInt(nextId)
            out.writeInt(things.size)
            for (t in things) {
                out.writeInt(t.id); out.writeUTF(t.word); out.writeUTF(t.cat); out.writeUTF(t.nickname ?: "")
                out.writeBoolean(t.taught); out.writeInt(t.count); out.writeLong(t.firstSeen); out.writeLong(t.lastSeen)
                out.writeInt(t.embeddings.size)
                for (v in t.embeddings) for (x in v) out.writeFloat(x)
            }
        }
        tmp.renameTo(file)
    }

    fun clear() {
        things.clear()
        nextId = 1
        file.delete()
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0 until dim) s += a[i] * b[i]
        return s
    }

    companion object {
        const val RECOGNIZE = 0.80
        /** Sightings of the same object before it earns a nickname. */
        const val NAME_AFTER = 3
        const val MAX_ANGLES = 6
        private const val MAGIC = 0x7417_0001
    }
}

/** Code-made pet names for objects. Never written by the text model. */
object Nicknames {
    fun make(word: String, seed: Long, id: Int): String {
        val r = SplittableRandom(seed xor (id.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor word.hashCode().toLong())
        val base = word.split(' ').last().replaceFirstChar { it.uppercase() }
        val stem = base.take(max(3, minOf(4, base.length)))
        val options = listOf(
            "${base}sby", "${stem}ster", "${stem}o", "Little $base", "Sir $base", "${stem}kins",
            "${stem}zo", "Old $base", "${base}bean", "${stem}y", "Lady $base", "${stem}bit",
        )
        repeat(20) {
            val n = options[r.nextInt(options.size)]
            if (n.length <= 14 && n.split(' ').all { Safety.isCleanWord(it) }) return n
        }
        return "Little $base"
    }
}
