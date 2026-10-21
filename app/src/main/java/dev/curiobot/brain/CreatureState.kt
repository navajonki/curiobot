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
    /** Current scavenger hunt, if any (see [Hunts]). */
    var huntId: String? = null
    var huntStartedAt = 0L
    var huntsDone = 0
    val recentHunts = mutableListOf<String>()
    /** Today's wish; a new one each creature-day. */
    var wishId: String? = null
    var wishDay = -1
    var wishGranted = false
    var taughtCount = 0
    /** Reflection: ideas the person kept, how much they like each kind of idea, recent combinations. */
    val ideas = mutableListOf<Idea>()
    val moveScores = linkedMapOf<String, Double>()
    val recentIdeaKeys = mutableListOf<String>()
    var ideasHad = 0
    var ideasKept = 0

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
        put("huntId", huntId ?: JSONObject.NULL)
        put("huntStartedAt", huntStartedAt)
        put("huntsDone", huntsDone)
        put("recentHunts", JSONArray(recentHunts))
        put("wishId", wishId ?: JSONObject.NULL)
        put("wishDay", wishDay)
        put("wishGranted", wishGranted)
        put("taughtCount", taughtCount)
        put("ideas", JSONArray().also { a ->
            for (i in ideas) a.put(JSONObject().apply {
                put("ts", i.ts); put("move", i.move); put("words", JSONArray(i.words)); put("text", i.text); put("hook", i.hook)
            })
        })
        put("moveScores", JSONObject().also { o -> moveScores.forEach { (k, v) -> o.put(k, v) } })
        put("recentIdeaKeys", JSONArray(recentIdeaKeys))
        put("ideasHad", ideasHad)
        put("ideasKept", ideasKept)
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
        put("hunt", huntId ?: JSONObject.NULL)
        put("wish", wishId ?: JSONObject.NULL)
        put("wish_granted", wishGranted)
        put("hunts_done", huntsDone)
        put("taught", taughtCount)
        put("ideas_kept", ideas.size)
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
            s.huntId = o.optStringOrNull("huntId")
            s.huntStartedAt = o.optLong("huntStartedAt", 0)
            s.huntsDone = o.optInt("huntsDone", 0)
            o.optJSONArray("recentHunts")?.let { a -> for (i in 0 until a.length()) s.recentHunts += a.getString(i) }
            s.wishId = o.optStringOrNull("wishId")
            s.wishDay = o.optInt("wishDay", -1)
            s.wishGranted = o.optBoolean("wishGranted", false)
            s.taughtCount = o.optInt("taughtCount", 0)
            o.optJSONArray("ideas")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    val w = x.optJSONArray("words")
                    s.ideas += Idea(x.getLong("ts"), x.optString("move"),
                        if (w == null) emptyList() else List(w.length()) { k -> w.getString(k) }, x.getString("text"),
                        x.optString("hook", ""))
                }
            }
            o.optJSONObject("moveScores")?.let { m -> for (k in m.keys()) s.moveScores[k] = m.getDouble(k) }
            o.optJSONArray("recentIdeaKeys")?.let { a -> for (i in 0 until a.length()) s.recentIdeaKeys += a.getString(i) }
            s.ideasHad = o.optInt("ideasHad", 0)
            s.ideasKept = o.optInt("ideasKept", 0)
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
