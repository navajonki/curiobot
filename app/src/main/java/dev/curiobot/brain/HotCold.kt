package dev.curiobot.brain

import kotlin.math.ln
import kotlin.random.Random

/**
 * Hot and cold: the creature thinks of something, gives a clue, and a meter
 * on the viewfinder says how close the camera is. "Close" is where the target
 * ranks among the eye's words for the current frame: the thing itself ranks
 * first, things that look related rank in the tens, unrelated things in the
 * hundreds. Targets come only from the safe, at-home hunt list.
 */
object HotCold {
    data class Round(val target: String, val cat: String, val clue: String, val startedAt: Long) {
        val ranks = ArrayDeque<Int>()
        var hits = 0
    }

    /** What the meter shows. */
    data class Reading(val label: String, val level: Double)

    private const val RECENT = 8

    /** Prefer safe things it has already seen here, so the round is winnable; otherwise any safe thing. */
    fun pick(s: CreatureState, catOf: (String) -> String?, now: Long, rng: Random): Round? {
        val safe = Hunts.WORDS.filter { catOf(it) != null && it !in s.recentGameTargets }
        if (safe.isEmpty()) return null
        val seenSafe = safe.filter { it in s.seen }
        val pool = if (seenSafe.isNotEmpty() && rng.nextInt(10) < 6) seenSafe else safe
        val target = pool[rng.nextInt(pool.size)]
        val cat = catOf(target) ?: ""
        val hooks = Ideas.HOOKS[cat] ?: listOf("is somewhere around here")
        val clue = "something that " + Ideas.singular(hooks[rng.nextInt(hooks.size)])
        s.recentGameTargets += target
        while (s.recentGameTargets.size > RECENT) s.recentGameTargets.removeAt(0)
        s.gamesPlayed++
        return Round(target, cat, clue, now)
    }

    /** Smooth over the last few frames so the meter doesn't flicker. */
    fun read(r: Round, rank: Int): Reading {
        r.ranks.addLast(rank)
        while (r.ranks.size > 3) r.ranks.removeFirst()
        val m = r.ranks.sorted()[r.ranks.size / 2]
        val label = when {
            m <= 1 -> "HOT!"
            m <= 4 -> "very warm"
            m <= 15 -> "warmer"
            m <= 50 -> "warm"
            m <= 150 -> "cool"
            else -> "cold"
        }
        val level = (1.0 - ln(m.toDouble()) / ln(300.0)).coerceIn(0.03, 1.0)
        return Reading(label, level)
    }

    /** Two frames in a row where the target is the eye's top word (and fairly sure) counts as found. */
    fun isFound(r: Round, rank: Int, prob: Double): Boolean {
        r.hits = if (rank == 1 && prob >= 0.35) r.hits + 1 else 0
        return r.hits >= 2
    }

    /** Extra hints over time. */
    fun hint(r: Round, now: Long): String? {
        val secs = (now - r.startedAt) / 1000
        return when {
            secs >= 60 -> "starts with '${r.target.first()}', ${r.target.length} letters"
            secs >= 30 -> "starts with '${r.target.first()}'"
            else -> null
        }
    }

    fun won(s: CreatureState, r: Round, now: Long): Int {
        val secs = ((now - r.startedAt) / 1000).toInt().coerceAtLeast(1)
        s.gamesWon++
        if (s.bestGameSeconds == 0 || secs < s.bestGameSeconds) s.bestGameSeconds = secs
        s.addJournal(JournalEntry(now, r.target, Mood.DELIGHTED.word,
            "Day ${Mind.dayNumber(s, now)}: played hot and cold and found ${withArticle(r.target)} in $secs seconds."))
        return secs
    }

    val INTRO = listOf("I'm thinking of {label}. Can you find it?", "Hot and cold! I'm thinking of {label}.")
    val WIN = listOf("Found it! {Word}! That took {n} seconds.", "Yes! {Word}! {n} seconds!", "That's it! {Word}!")
    val GIVE_UP = listOf("It was {word}! Next time.", "Hehe. It was {word}.")
    val NOT_THAT = "not that one"
}
