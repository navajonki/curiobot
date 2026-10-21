package dev.curiobot.brain

import org.json.JSONArray
import org.json.JSONObject

data class Sighting(
    var count: Int,
    val first: Long,
    var last: Long,
    var lastMood: String,
    val cat: String,
)

data class JournalEntry(val ts: Long, val word: String?, val mood: String, val text: String)

/**
 * The creature's whole persistent self. Only code writes these fields; the text
 * model reads a summary and never changes anything here.
 */
class CreatureState(
    val seed: Long,
    val bornAt: Long,
) {
    /** How full its curiosity is, 0..1. Low means bored, never sick. */
    var satisfaction = 0.6
    var energy = 1.0
    var asleep = false
    var sleepUntil = 0L
    var dreamShown = false
    var lastTick = bornAt
    var interactions = 0L
    var focusWord: String? = null
    var focusCat: String? = null
    var focusKind: String? = null
    var focusAt = 0L
    val seen = linkedMapOf<String, Sighting>()
    val journal = mutableListOf<JournalEntry>()

    val traits: Traits by lazy { Genome.traitsFor(seed) }

    fun addJournal(e: JournalEntry) {
        journal += e
        while (journal.size > MAX_JOURNAL) journal.removeAt(0)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("seed", seed)
        put("bornAt", bornAt)
        put("satisfaction", satisfaction)
        put("energy", energy)
        put("asleep", asleep)
        put("sleepUntil", sleepUntil)
        put("dreamShown", dreamShown)
        put("lastTick", lastTick)
        put("interactions", interactions)
        put("focusWord", focusWord ?: JSONObject.NULL)
        put("focusCat", focusCat ?: JSONObject.NULL)
        put("focusKind", focusKind ?: JSONObject.NULL)
        put("focusAt", focusAt)
        put("seen", JSONObject().also { o ->
            for ((w, s) in seen) o.put(w, JSONObject().apply {
                put("count", s.count); put("first", s.first); put("last", s.last)
                put("lastMood", s.lastMood); put("cat", s.cat)
            })
        })
        put("journal", JSONArray().also { a ->
            for (e in journal) a.put(JSONObject().apply {
                put("ts", e.ts); put("word", e.word ?: JSONObject.NULL); put("mood", e.mood); put("text", e.text)
            })
        })
    }

    /** A compact summary for logs. */
    fun summary(): JSONObject = JSONObject().apply {
        put("name", traits.name)
        put("satisfaction", round2(satisfaction))
        put("energy", round2(energy))
        put("asleep", asleep)
        put("words_known", seen.size)
        put("interactions", interactions)
        put("focus", focusWord ?: JSONObject.NULL)
    }

    companion object {
        const val MAX_JOURNAL = 60

        fun fromJson(o: JSONObject): CreatureState {
            val s = CreatureState(o.getLong("seed"), o.getLong("bornAt"))
            s.satisfaction = o.optDouble("satisfaction", 0.6)
            s.energy = o.optDouble("energy", 1.0)
            s.asleep = o.optBoolean("asleep", false)
            s.sleepUntil = o.optLong("sleepUntil", 0)
            s.dreamShown = o.optBoolean("dreamShown", false)
            s.lastTick = o.optLong("lastTick", s.bornAt)
            s.interactions = o.optLong("interactions", 0)
            s.focusWord = o.optStringOrNull("focusWord")
            s.focusCat = o.optStringOrNull("focusCat")
            s.focusKind = o.optStringOrNull("focusKind")
            s.focusAt = o.optLong("focusAt", 0)
            o.optJSONObject("seen")?.let { seen ->
                for (w in seen.keys()) {
                    val x = seen.getJSONObject(w)
                    s.seen[w] = Sighting(x.getInt("count"), x.getLong("first"), x.getLong("last"),
                        x.optString("lastMood", "content"), x.optString("cat", ""))
                }
            }
            o.optJSONArray("journal")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    s.journal += JournalEntry(x.getLong("ts"), x.optStringOrNull("word"),
                        x.optString("mood", "content"), x.getString("text"))
                }
            }
            return s
        }

        private fun JSONObject.optStringOrNull(k: String): String? =
            if (isNull(k) || !has(k)) null else optString(k)
    }
}

internal fun round2(d: Double) = Math.round(d * 100) / 100.0
