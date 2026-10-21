package dev.curiobot.brain

import kotlin.math.max
import kotlin.math.min

/**
 * How fast creature time runs. "Test pace" turns hours into minutes so needs,
 * boredom, naps and daily wishes are testable in one sitting.
 */
data class Pace(val test: Boolean) {
    val unitMs: Long = if (test) 60_000L else 3_600_000L
    val napMs: Long = if (test) 120_000L else 45 * 60_000L
    /** A creature day: 24 units (24 minutes at test pace). */
    val dayMs: Long = unitMs * 24
}

enum class Mood(val word: String) {
    DELIGHTED("delighted"), CURIOUS("curious"), CONTENT("content"), MEH("meh"),
    BORED("bored"), WARY("wary"), SLEEPY("sleepy"), FRIENDLY("friendly"),
    PUZZLED("puzzled"), QUIET("quiet"),
}

/** What the vision side saw, reduced to what the brain needs. */
enum class Verdict { NORMAL, PERSON, LOWINFO, UNSURE, TRIPWIRE }

data class Seeing(
    val verdict: Verdict,
    val label: String,
    val cat: String,
    val confidence: Double,
    /** 1 = unlike anything seen before, 0 = a photo it has basically seen. */
    val imageNovelty: Double,
)

/**
 * Everything the person can choose. [ask] is how the intent reads to the text
 * model; [labels] are code-written labels ({word} gets the focus word).
 * Intents with [modelLabel] = false always use the written label.
 */
enum class Intent(val id: String, val ask: String, val labels: List<String>, val modelLabel: Boolean = true) {
    ASK_ABOUT("ask_about", "ask what it thinks", listOf("What do you think?", "Tell me about {word}")),
    SHOW_NEW("show_new", "show something new", listOf("Show something new", "Find something new")),
    REMEMBER("remember", "remember a day", listOf("Remember a day", "Remember something")),
    NAP("nap", "nap", listOf("Nap time", "Time to rest")),
    SAY_HI("say_hi", "say hello back", listOf("Say hello", "Wave hello")),
    CORRECT("correct", "", listOf("Not quite"), modelLabel = false),
    MENU("menu", "", listOf("Other things"), modelLabel = false),
    HUNT("hunt", "", listOf("Go on a hunt"), modelLabel = false),
    HUNT_SKIP("hunt_skip", "", listOf("A different hunt"), modelLabel = false),
    WISH("wish", "", listOf("What's your wish?"), modelLabel = false),
    TEACH_PICK("teach_pick", "", listOf("{word}"), modelLabel = false),
    TEACH_MORE("teach_more", "", listOf("Other guesses"), modelLabel = false),
    TEACH_NONE("teach_none", "", listOf("None of these"), modelLabel = false),
    LET_SLEEP("let_sleep", "", listOf("Let it sleep"), modelLabel = false),
    PEEK("peek", "", listOf("Peek at its dream"), modelLabel = false),
    WAKE("wake", "", listOf("Wake it gently"), modelLabel = false),
    TRY_AGAIN("try_again", "", listOf("Try again"), modelLabel = false);

    fun fallbackLabel(word: String?, variant: Int): String {
        val usable = labels.filter { word != null || !it.contains("{word}") }.ifEmpty { labels }
        return usable[Math.floorMod(variant, usable.size)]
            .replace("{word}", (word ?: "").replaceFirstChar { it.uppercase() })
    }
}

/** The word kept for things the person said none of the guesses fit. */
const val MYSTERY = "mystery thing"

fun withArticle(w: String) = (if (w.isNotEmpty() && w[0].lowercaseChar() in "aeiou") "an " else "a ") + w

/** What happened this turn, as the text model hears it. */
sealed class Event {
    abstract val requiredWord: String?
    open val aboutPerson: Boolean = false
    abstract fun promptLine(): String

    data class Saw(val word: String, val firstTime: Boolean, val count: Int, val nickname: String? = null) : Event() {
        override val requiredWord get() = nickname ?: word
        override fun promptLine() = if (nickname != null) "saw: $nickname the $word (an old friend, seen $count times)"
        else "saw: $word (" + (if (firstTime) "first time" else "seen $count times") + ")"
    }
    data class SawPerson(val firstTime: Boolean) : Event() {
        override val requiredWord: String? get() = null
        override val aboutPerson get() = true
        override fun promptLine() = "saw: a person (greet them, never describe them)"
    }
    data class About(val word: String) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "event: saying what the $word reminds it of"
    }
    data class Taught(val word: String) : Event() {
        /** "None of these" leaves it without a word, so there is nothing to require. */
        val unnamed get() = word == MYSTERY
        override val requiredWord get() = word.takeUnless { unnamed }
        override fun promptLine() =
            if (unnamed) "event: has no word for this thing yet, but will remember how it looks"
            else "event: learned that this is really ${withArticle(word)}, and will remember it"
    }
    data class Found(val word: String, val huntLabel: String) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "event: was hunting for $huntLabel and found ${withArticle(word)}"
    }
    data class WishGranted(val word: String, val wishLabel: String) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "event: its wish for $wishLabel came true when it saw ${withArticle(word)}"
    }
    data class Named(val word: String, val nickname: String) : Event() {
        override val requiredWord get() = nickname
        override fun promptLine() = "event: named this $word $nickname"
    }
    object Hello : Event() {
        override val requiredWord: String? get() = null
        override val aboutPerson get() = true
        override fun promptLine() = "event: the person said hello"
    }
    data class Recall(val entry: JournalEntry) : Event() {
        override val requiredWord get() = entry.word?.takeIf { it != "person" }
        override fun promptLine() = "event: remembering this: " + entry.text.replace(Regex("^Day \\d+: "), "")
    }
    object Woke : Event() {
        override val requiredWord: String? get() = null
        override fun promptLine() = "event: just woke up from a nap"
    }
}

data class Reaction(
    val mood: Mood,
    val firstTime: Boolean,
    val count: Int,
    val score: Double,
    val satisfactionDelta: Double,
    val energyDelta: Double,
    val journal: JournalEntry?,
)

object Mind {
    const val DOZE_BELOW = 0.15
    /** Below this, a nap is offered. */
    const val TIRED = 0.45
    const val ACTION_ENERGY = 0.06

    /** Apply elapsed time. Returns true if the creature woke up from a nap. */
    fun tick(s: CreatureState, now: Long, pace: Pace): Boolean {
        val units = max(0L, now - s.lastTick).toDouble() / pace.unitMs
        s.lastTick = now
        // Hunts quietly expire after a creature-day; nothing bad happens.
        if (s.huntId != null && now - s.huntStartedAt > pace.dayMs) s.huntId = null
        if (s.asleep) {
            if (now >= s.sleepUntil) {
                s.asleep = false
                s.energy = 1.0
                s.dreamShown = false
                return true
            }
            return false
        }
        val t = s.traits
        s.satisfaction = clamp(s.satisfaction - units * 0.08 * (0.6 + t.curiosity * 0.6))
        s.energy = clamp(s.energy + units * 0.03)
        return false
    }

    /**
     * React to something seen. With [applyNeeds] = false (a correction after the
     * fact) the needs were already counted, so only the memory and mood update.
     */
    fun react(s: CreatureState, seeing: Seeing, now: Long, applyNeeds: Boolean = true): Reaction {
        val t = s.traits
        val isPerson = seeing.verdict == Verdict.PERSON
        val word = if (isPerson) "person" else seeing.label
        val prior = s.seen[word]
        val firstTime = prior == null
        val countBefore = prior?.count ?: 0
        val wordNovelty = if (firstTime) 1.0 else 1.0 / (1 + countBefore)
        val affinity = when {
            isPerson -> 0.25 - 0.3 * t.shyness
            seeing.cat in t.loves -> 0.45
            seeing.cat == t.dislikes -> -0.55
            else -> 0.0
        }
        val score = affinity + 0.55 * wordNovelty + 0.25 * seeing.imageNovelty + 0.1 * (t.curiosity - 0.7)
        val mood = when {
            isPerson -> Mood.FRIENDLY
            seeing.cat == t.dislikes && score < 0.35 -> Mood.WARY
            score >= 0.75 -> Mood.DELIGHTED
            score >= 0.4 -> Mood.CURIOUS
            countBefore >= 3 && score < 0.25 -> Mood.BORED
            score >= 0.15 -> Mood.CONTENT
            else -> Mood.MEH
        }
        val before = Pair(s.satisfaction, s.energy)
        if (applyNeeds) {
            val satDelta = 0.35 * (0.6 * wordNovelty + 0.4 * seeing.imageNovelty) * (if (mood == Mood.WARY) 0.5 else 1.0)
            s.satisfaction = clamp(s.satisfaction + satDelta)
            s.energy = clamp(s.energy - ACTION_ENERGY)
            s.interactions++
        }
        val sighting = prior ?: Sighting(0, now, now, mood.word, if (isPerson) "person" else seeing.cat)
        sighting.count++
        sighting.last = now
        sighting.lastMood = mood.word
        s.seen[word] = sighting
        s.focusWord = word
        s.focusCat = sighting.cat
        s.focusKind = if (isPerson) "person" else "normal"
        s.focusAt = now

        val day = dayNumber(s, now)
        val entry = when {
            isPerson && firstTime -> JournalEntry(now, "person", mood.word, "Day $day: met a person.")
            isPerson -> null
            firstTime -> JournalEntry(now, word, mood.word, "Day $day: first $word. ${moodPhrase(mood)}")
            mood == Mood.DELIGHTED && sighting.count % 5 == 0 ->
                JournalEntry(now, word, mood.word, "Day $day: saw the $word again. Still loved it.")
            else -> null
        }
        entry?.let { s.addJournal(it) }
        return Reaction(mood, firstTime, sighting.count, score, s.satisfaction - before.first,
            s.energy - before.second, entry)
    }

    /** Undo a sighting that turned out to be mislabeled (before teaching relabels it). */
    fun unrecord(s: CreatureState, word: String, at: Long) {
        val sg = s.seen[word] ?: return
        sg.count--
        if (sg.count <= 0) s.seen.remove(word)
        s.journal.removeAll { it.word == word && it.ts == at }
    }

    /** The resting mood when nothing new just happened. */
    fun idleMood(s: CreatureState): Mood = when {
        s.asleep -> Mood.SLEEPY
        s.energy < 0.3 -> Mood.SLEEPY
        s.satisfaction < 0.3 -> Mood.BORED
        s.satisfaction > 0.75 -> Mood.CONTENT
        else -> Mood.CURIOUS
    }

    /** The options to offer. [fresh] is true right after something was seen or taught. */
    fun intentsFor(s: CreatureState, fresh: Boolean): List<Intent> {
        if (s.asleep) return if (s.dreamShown) listOf(Intent.LET_SLEEP, Intent.WAKE)
            else listOf(Intent.LET_SLEEP, Intent.PEEK, Intent.WAKE)
        if (fresh && s.focusKind == "person") return listOf(Intent.SAY_HI, Intent.SHOW_NEW, Intent.MENU)
        if (fresh && s.focusWord != null) return listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW, Intent.MENU)
        val out = mutableListOf<Intent>()
        if (s.energy < TIRED) out += Intent.NAP
        out += Intent.SHOW_NEW
        out += if (s.huntId != null) Intent.HUNT_SKIP else Intent.HUNT
        if (s.wishId != null && !s.wishGranted) out += Intent.WISH
        if (s.journal.isNotEmpty()) out += Intent.REMEMBER
        return out.take(4)
    }

    /** Small costs for actions other than seeing. */
    fun spend(s: CreatureState, intent: Intent) {
        when (intent) {
            Intent.ASK_ABOUT, Intent.SAY_HI -> {
                s.energy = clamp(s.energy - ACTION_ENERGY / 2); s.satisfaction = clamp(s.satisfaction + 0.03)
            }
            Intent.REMEMBER -> s.energy = clamp(s.energy - ACTION_ENERGY / 3)
            else -> {}
        }
        s.interactions++
    }

    fun startHunt(s: CreatureState, h: Hunt, now: Long) {
        s.huntId = h.id
        s.huntStartedAt = now
        s.recentHunts += h.id
        while (s.recentHunts.size > 5) s.recentHunts.removeAt(0)
    }

    fun completeHunt(s: CreatureState, h: Hunt, word: String, now: Long): JournalEntry {
        s.huntId = null
        s.huntsDone++
        s.satisfaction = clamp(s.satisfaction + 0.25)
        return JournalEntry(now, word, Mood.DELIGHTED.word, "Day ${dayNumber(s, now)}: found ${h.label} on a hunt: a $word.")
            .also { s.addJournal(it) }
    }

    fun dayIndex(s: CreatureState, now: Long, pace: Pace) = ((now - s.bornAt) / pace.dayMs).toInt()

    /** Make today's wish if the day has turned. Returns the new wish, or null. */
    fun ensureWish(s: CreatureState, now: Long, pace: Pace, roll: Int): Hunt? {
        val today = dayIndex(s, now, pace)
        if (s.wishDay == today && s.wishId != null) return null
        val w = Hunts.pickWish(s, roll)
        s.wishId = w.id
        s.wishDay = today
        s.wishGranted = false
        return w
    }

    fun grantWish(s: CreatureState, w: Hunt, word: String, now: Long): JournalEntry {
        s.wishGranted = true
        s.satisfaction = clamp(s.satisfaction + 0.25)
        return JournalEntry(now, word, Mood.DELIGHTED.word, "Day ${dayNumber(s, now)}: my wish for ${w.label} came true: a $word.")
            .also { s.addJournal(it) }
    }

    fun named(s: CreatureState, word: String, nickname: String, now: Long): JournalEntry =
        JournalEntry(now, word, Mood.DELIGHTED.word, "Day ${dayNumber(s, now)}: named the $word $nickname.")
            .also { s.addJournal(it) }

    fun startNap(s: CreatureState, now: Long, pace: Pace) {
        s.asleep = true
        s.sleepUntil = now + pace.napMs
        s.dreamShown = false
        s.focusWord = null
        s.focusKind = null
    }

    /** Woken early: keeps whatever rest it got so far, no penalty. */
    fun wakeEarly(s: CreatureState, now: Long, pace: Pace): Double {
        val start = s.sleepUntil - pace.napMs
        val progress = ((now - start).toDouble() / pace.napMs).coerceIn(0.0, 1.0)
        s.energy = clamp(s.energy + (1.0 - s.energy) * progress)
        s.asleep = false
        s.dreamShown = false
        s.lastTick = now
        return progress
    }

    fun shouldDoze(s: CreatureState) = !s.asleep && s.energy < DOZE_BELOW

    /** Prefer happy memories, but any will do. */
    fun pickMemory(s: CreatureState, roll: Int): JournalEntry? {
        if (s.journal.isEmpty()) return null
        val happy = s.journal.filter { it.mood == Mood.DELIGHTED.word || it.mood == Mood.CURIOUS.word }
        val pool = if (happy.isNotEmpty() && roll % 3 != 0) happy else s.journal
        return pool[Math.floorMod(roll, pool.size)]
    }

    fun dayNumber(s: CreatureState, now: Long) = ((now - s.bornAt) / 86_400_000L).toInt() + 1

    private fun moodPhrase(m: Mood) = when (m) {
        Mood.DELIGHTED -> "Loved it."
        Mood.CURIOUS -> "Wondered about it."
        Mood.WARY -> "Not for me."
        Mood.CONTENT -> "It was nice."
        Mood.BORED, Mood.MEH -> "It was fine."
        else -> "Hm."
    }

    private fun clamp(d: Double) = min(1.0, max(0.0, d))
}
