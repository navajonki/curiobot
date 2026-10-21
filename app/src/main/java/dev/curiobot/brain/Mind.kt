package dev.curiobot.brain

import kotlin.math.max
import kotlin.math.min

/**
 * How fast creature time runs. "Test pace" turns hours into minutes so needs,
 * boredom and naps are testable in one sitting.
 */
data class Pace(val test: Boolean) {
    val unitMs: Long = if (test) 60_000L else 3_600_000L
    val napMs: Long = if (test) 120_000L else 45 * 60_000L
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
 * model; [labels] are code-written fallbacks ({word} gets the focus word).
 */
enum class Intent(val id: String, val ask: String, val labels: List<String>, val modelLabel: Boolean = true) {
    LOOK_CLOSER("look_closer", "look closer", listOf("Look closer", "Look closer at {word}")),
    ASK_ABOUT("ask_about", "ask what it thinks", listOf("What do you think?", "Tell me about {word}")),
    SHOW_NEW("show_new", "show something new", listOf("Show something new", "Find something new")),
    PLAY("play", "play a game", listOf("Play a game", "Let's play")),
    REMEMBER("remember", "remember a day", listOf("Remember a day", "Remember something")),
    NAP("nap", "nap", listOf("Nap time", "Time to rest")),
    SAY_HI("say_hi", "say hello back", listOf("Say hello", "Wave hello")),
    LET_SLEEP("let_sleep", "let it sleep", listOf("Let it sleep"), modelLabel = false),
    PEEK("peek", "peek at its dream", listOf("Peek at its dream"), modelLabel = false),
    GUESS_LEFT("guess_left", "guess left", listOf("Left side"), modelLabel = false),
    GUESS_RIGHT("guess_right", "guess right", listOf("Right side"), modelLabel = false),
    TRY_AGAIN("try_again", "try again", listOf("Try again"), modelLabel = false);

    fun fallbackLabel(word: String?, variant: Int): String {
        val usable = labels.filter { word != null || !it.contains("{word}") }
        return usable[Math.floorMod(variant, usable.size)].replace("{word}", word ?: "")
    }
}

/** What happened this turn, as the text model hears it. */
sealed class Event {
    abstract val requiredWord: String?
    open val aboutPerson: Boolean = false
    abstract fun promptLine(): String

    data class Saw(val word: String, val firstTime: Boolean, val count: Int) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "saw: $word (" + (if (firstTime) "first time" else "seen $count times") + ")"
    }
    data class SawPerson(val firstTime: Boolean) : Event() {
        override val requiredWord: String? get() = null
        override val aboutPerson get() = true
        override fun promptLine() = "saw: a person (greet them, never describe them)"
    }
    data class Closer(val word: String) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "event: looking closer at the $word"
    }
    data class About(val word: String) : Event() {
        override val requiredWord get() = word
        override fun promptLine() = "event: saying what the $word reminds it of"
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
    object Idle : Event() {
        override val requiredWord: String? get() = null
        override fun promptLine() = "event: waiting for something new to look at"
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
    const val ACTION_ENERGY = 0.06

    /** Apply elapsed time. Returns true if the creature woke up from a nap. */
    fun tick(s: CreatureState, now: Long, pace: Pace): Boolean {
        val units = max(0L, now - s.lastTick).toDouble() / pace.unitMs
        s.lastTick = now
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

    fun react(s: CreatureState, seeing: Seeing, now: Long): Reaction {
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
        val satDelta = 0.35 * (0.6 * wordNovelty + 0.4 * seeing.imageNovelty) *
            (if (mood == Mood.WARY) 0.5 else 1.0)
        val before = Pair(s.satisfaction, s.energy)
        s.satisfaction = clamp(s.satisfaction + satDelta)
        s.energy = clamp(s.energy - ACTION_ENERGY)
        s.interactions++
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

    /** The resting mood when nothing new just happened. */
    fun idleMood(s: CreatureState): Mood = when {
        s.asleep -> Mood.SLEEPY
        s.energy < 0.3 -> Mood.SLEEPY
        s.satisfaction < 0.3 -> Mood.BORED
        s.satisfaction > 0.75 -> Mood.CONTENT
        else -> Mood.CURIOUS
    }

    /** The options to offer. [fresh] is true right after something was seen. */
    fun intentsFor(s: CreatureState, fresh: Boolean): List<Intent> {
        if (s.asleep) return if (s.dreamShown) listOf(Intent.LET_SLEEP) else listOf(Intent.LET_SLEEP, Intent.PEEK)
        val canRemember = s.journal.isNotEmpty()
        val tired = s.energy < 0.4
        val out = mutableListOf<Intent>()
        when {
            fresh && s.focusKind == "person" -> {
                out += Intent.SAY_HI; out += Intent.SHOW_NEW; out += Intent.PLAY
                out += if (canRemember) Intent.REMEMBER else Intent.NAP
            }
            fresh && s.focusWord != null -> {
                out += Intent.LOOK_CLOSER; out += Intent.ASK_ABOUT; out += Intent.SHOW_NEW
                out += if (tired) Intent.NAP else Intent.PLAY
            }
            tired -> {
                out += Intent.NAP; out += Intent.SHOW_NEW
                if (canRemember) out += Intent.REMEMBER
                out += Intent.PLAY
            }
            else -> {
                out += Intent.SHOW_NEW; out += Intent.PLAY
                if (canRemember) out += Intent.REMEMBER
                out += Intent.NAP
            }
        }
        return out
    }

    /** Small costs for actions other than seeing. */
    fun spend(s: CreatureState, intent: Intent) {
        when (intent) {
            Intent.LOOK_CLOSER, Intent.ASK_ABOUT, Intent.SAY_HI -> {
                s.energy = clamp(s.energy - ACTION_ENERGY / 2); s.satisfaction = clamp(s.satisfaction + 0.03)
            }
            Intent.GUESS_LEFT, Intent.GUESS_RIGHT -> {
                s.energy = clamp(s.energy - ACTION_ENERGY); s.satisfaction = clamp(s.satisfaction + 0.05)
            }
            Intent.REMEMBER -> s.energy = clamp(s.energy - ACTION_ENERGY / 3)
            else -> {}
        }
        s.interactions++
    }

    fun startNap(s: CreatureState, now: Long, pace: Pace) {
        s.asleep = true
        s.sleepUntil = now + pace.napMs
        s.dreamShown = false
        s.focusWord = null
        s.focusKind = null
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
