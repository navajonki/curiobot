package dev.curiobot.brain

import kotlin.random.Random

/**
 * Dreams: peek at a nap and watch a three-page dream built from the things it
 * saw that day. Like tiny adventures, code writes what happens (from cozy,
 * silly lists) and the text model only narrates each page, sleepily.
 * Finished dreams go in the storybook.
 */
object Dreams {
    /** Each page has a key word the narration must keep, so the line carries the dream. */
    private val OPENINGS = listOf(
        "{a} and {b} drift up in a giant soap bubble" to "bubble",
        "{a} and {b} sail a paper boat across the rug" to "boat",
        "{a} and {b} have tea on the moon" to "moon",
        "{a} grows as big as a house, and {b} climbs up to say hi" to "house",
        "{a} and {b} bounce on a cloud like a trampoline" to "cloud",
    )
    private val MIDDLES = listOf(
        "{b} starts to sing a slow, sleepy song" to "song",
        "{a} and {b} swap colors" to "colors",
        "the floor under {a} turns into soft pudding" to "pudding",
        "the stars come down to dance with {a}" to "stars",
        "lots of tiny {cs} rain down like confetti" to "confetti",
        "{b} learns to whistle" to "whistle",
    )
    private val ENDINGS = listOf(
        "{a} and {b} drift down like feathers and land in my nest" to "nest",
        "{a} and {b} curl up and fall asleep too" to "asleep",
        "{a} waves goodbye and the dream goes fuzzy" to "fuzzy",
        "{b} turns into a big cozy blanket for {a}" to "blanket",
    )

    class Run(val a: Ideas.Thing, val b: Ideas.Thing, val c: Ideas.Thing?, rng: Random) {
        private val beats: List<Pair<String, String>> = listOf(
            OPENINGS[rng.nextInt(OPENINGS.size)],
            MIDDLES.filter { c != null || "{cs}" !in it.first }.let { it[rng.nextInt(it.size)] },
            ENDINGS[rng.nextInt(ENDINGS.size)],
        )
        var page = 0; private set
        val pages = mutableListOf<String>()
        val done get() = page >= beats.size
        val last get() = page == beats.size - 1
        val title = "A dream about ${a.name} and ${b.name}"

        /** What happens on this page, written by code. */
        fun content(): String = beats[page].first.replace("{a}", a.phrase).replace("{b}", b.phrase)
            .replace("{cs}", c?.word?.let { plural(it) } ?: "")

        fun key() = beats[page].second

        /** Whoever this page is about (by name), plus its key word. */
        fun required(): List<String> {
            val t = beats[page].first
            val who = listOfNotNull(a.name.takeIf { "{a}" in t }, b.name.takeIf { "{b}" in t }, c?.word?.takeIf { "{cs}" in t })
            return who.take(2) + key()
        }

        fun context() = (listOf(a.word, b.word, content(), "dream sleep zzz") + listOfNotNull(a.nickname, b.nickname, c?.word))
            .joinToString(" ")

        fun next() { page++ }
    }

    /** Things seen today star in the dream; older ones fill in if the day was quiet. */
    fun start(s: CreatureState, nicknames: Map<String, String>, now: Long, dayMs: Long, rng: Random): Run? {
        val all = Ideas.material(s, nicknames)
        if (all.size < 2) return null
        val today = all.filter { (s.seen[it.word]?.last ?: 0) >= now - dayMs }
        val pool = (today.shuffled(rng) + all.filter { it !in today }).take(3)
        return Run(pool[0], pool[1], pool.getOrNull(2), rng)
    }

    fun plural(w: String) = when {
        w.endsWith("s") || w.endsWith("x") || w.endsWith("ch") || w.endsWith("sh") -> w + "es"
        w.endsWith("y") && w.length > 1 && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"
        else -> w + "s"
    }

    fun promptLine(r: Run) = "event: in my dream, ${r.content()}" + if (r.last) ", the end of the dream" else ""

    /** Written narration for when the model's line doesn't pass. */
    fun fallback(r: Run, roll: Int): String {
        val c = r.content()
        return when (r.page) {
            0 -> listOf("Zzz... I dream that $c...", "Mm... in my dream, $c...")
            else -> listOf("Zzz... then $c...", "Mm... and $c...")
        }[Math.floorMod(roll, 2)]
    }

    fun save(s: CreatureState, r: Run, now: Long): Story {
        val story = Story(now, r.title, r.pages.toList())
        s.stories += story
        while (s.stories.size > Stories.MAX_KEPT) s.stories.removeAt(0)
        s.addJournal(JournalEntry(now, r.a.word, Mood.CONTENT.word,
            "Day ${Mind.dayNumber(s, now)}: dreamed about ${r.a.name} and ${r.b.name}."))
        return story
    }

    val SAVED = listOf("Zzz... that dream goes in my storybook... zzz.")
}

/**
 * The memory game: show it five things and it says whether it has seen each
 * exact one before (by its image memory, so a different mug can trick it).
 * You say if it's right. The game never changes what it remembers.
 */
object MemoryGame {
    const val ROUNDS = 5
    /** Same object, re-shot, within one game. */
    const val SAME_IN_GAME = 0.85

    class Run {
        var round = 1
        var score = 0
        /** What this game has been shown, so a repeat within the game counts as seen. */
        val shown = mutableListOf<FloatArray>()
        var guessedSeen = false
        val over get() = round > ROUNDS
    }

    fun shownBefore(r: Run, e: FloatArray): Boolean = r.shown.any { v -> dot(v, e) >= SAME_IN_GAME }

    /** Record the person's verdict, and the end of the game in the stats and journal. */
    fun judge(s: CreatureState, r: Run, right: Boolean, now: Long): Boolean {
        if (right) r.score++
        r.round++
        if (!r.over) return false
        s.memoryGames++
        s.memoryBest = maxOf(s.memoryBest, r.score)
        s.addJournal(JournalEntry(now, null, if (r.score >= 4) Mood.DELIGHTED.word else Mood.CONTENT.word,
            "Day ${Mind.dayNumber(s, now)}: played the memory game and got ${r.score} of $ROUNDS."))
        return true
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    val INTRO = listOf("Memory game! Show me $ROUNDS things. I'll say if I've seen each one before. Try to trick me!")
    val SEEN = listOf("I know this one! It's {label}!", "I've seen this before. {Label}!")
    val SEEN_IN_GAME = listOf("You just showed me that one!", "Ha, that one again! I remember.")
    val NEW = listOf("Never seen this one before. It's new!", "Hmm... this one is new to me.")
    val RIGHT = listOf("I knew it!", "Yes! My memory works.")
    val WRONG = listOf("Oops! You got me.", "Ha! That one tricked me.")
    val NOT_A_THING = listOf("I can only play with things. Show me a thing!")
    fun end(score: Int) = when {
        score == ROUNDS -> "All $ROUNDS! I remember everything!"
        score >= 3 -> "I got $score of $ROUNDS. Not bad!"
        else -> "Only $score of $ROUNDS. Those were tricky!"
    }
}
