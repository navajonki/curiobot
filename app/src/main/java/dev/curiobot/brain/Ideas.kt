package dev.curiobot.brain

import kotlin.random.Random

/** A thought the creature had and the person chose to keep ("That's cool!"). */
data class Idea(val ts: Long, val move: String, val words: List<String>, val text: String)

/**
 * Reflection: the creature combines things from its past into a new thought.
 *
 * Code does the thinking that matters: it picks what to combine (things it has
 * seen, nicknamed friends, nest kinds, ideas it already kept) and how (a
 * [Move]). The text model only puts that into words, and the person decides
 * whether the idea is kept. Kept ideas can be built on later, and the moves
 * the person likes become more likely, so its way of thinking drifts toward
 * what its person enjoys.
 */
object Ideas {
    const val MAX_KEPT = 40
    private const val RECENT_KEYS = 12
    const val SCORE_START = 1.0
    private const val SCORE_MIN = 0.3
    private const val SCORE_MAX = 3.0

    enum class Move(val id: String) {
        COMMON("common"),            // what two things share
        FRIENDS("friends"),          // two things as friends
        HELPS("helps"),              // how one could help the other
        KIND_RULE("kind_rule"),      // a guess about a whole kind in the nest
        FAVORITE_WHY("favorite_why"),// why it likes its favorite kind
        BUILD_ON("build_on"),        // adds something new to a kept idea
    }

    /** Something it can think about. [name] is what the line must say. */
    data class Thing(val word: String, val cat: String, val nickname: String?) {
        val name get() = nickname ?: word
        val phrase get() = nickname?.let { "$it the $word" } ?: "the $word"
    }

    data class Plan(val move: Move, val things: List<Thing>, val kinds: String? = null, val idea: Idea? = null) {
        val key get() = move.id + ":" + things.map { it.word }.sorted().joinToString("+")
        /** The words the line has to include. */
        val required: List<String> get() = when (move) {
            Move.KIND_RULE -> things.take(2).map { it.name }
            Move.FAVORITE_WHY, Move.BUILD_ON -> things.take(1).map { it.name }
            else -> things.map { it.name }
        }
    }

    /** Things it has seen, most recent first, with nicknames where it gave one. */
    fun material(s: CreatureState, nicknames: Map<String, String> = emptyMap()): List<Thing> =
        s.seen.entries.filter { it.value.cat in Nest.KINDS }
            .sortedByDescending { it.value.last }
            .map { Thing(it.key, it.value.cat, nicknames[it.key]) }

    fun canReflect(s: CreatureState) = material(s).size >= 2

    /** Pick a move (weighted by what the person has liked) and the things to combine. */
    fun plan(s: CreatureState, nicknames: Map<String, String>, rng: Random): Plan? {
        val things = material(s, nicknames)
        if (things.size < 2) return null
        val slots = Nest.slots(s)
        val bigKinds = slots.filter { it.words.size >= 3 }
        val fav = Nest.favorite(s)
        val moves = Move.values().filter { m ->
            when (m) {
                Move.KIND_RULE -> bigKinds.isNotEmpty()
                Move.FAVORITE_WHY -> fav != null
                Move.BUILD_ON -> s.ideas.isNotEmpty()
                else -> true
            }
        }
        repeat(12) {
            val move = weighted(moves, rng) { s.moveScores[it.id] ?: SCORE_START }
            val p = when (move) {
                Move.COMMON, Move.FRIENDS, Move.HELPS -> {
                    // One recent thing plus one from anywhere; "in common" is more fun across kinds.
                    val a = things[rng.nextInt(minOf(6, things.size))]
                    val pool = things.filter { it.word != a.word && (move != Move.COMMON || it.cat != a.cat) }
                        .ifEmpty { things.filter { it.word != a.word } }
                    Plan(move, listOf(a, pool[rng.nextInt(pool.size)]))
                }
                Move.KIND_RULE -> {
                    val slot = bigKinds[rng.nextInt(bigKinds.size)]
                    val picked = slot.words.shuffled(rng).take(3).mapNotNull { w -> things.firstOrNull { it.word == w } }
                    Plan(move, picked, kinds = Nest.NAMES[slot.kind])
                }
                Move.FAVORITE_WHY -> {
                    val inFav = things.filter { it.cat == fav }
                    Plan(move, listOf(inFav[rng.nextInt(inFav.size)]), kinds = Nest.NAMES[fav])
                }
                Move.BUILD_ON -> {
                    val idea = s.ideas[s.ideas.size - 1 - rng.nextInt(minOf(5, s.ideas.size))]
                    val fresh = things.filter { it.word !in idea.words && it.name !in idea.words }
                    if (fresh.isEmpty()) null else Plan(move, listOf(fresh[rng.nextInt(minOf(6, fresh.size))]), idea = idea)
                }
            }
            if (p != null && p.key !in s.recentIdeaKeys) {
                s.recentIdeaKeys += p.key
                while (s.recentIdeaKeys.size > RECENT_KEYS) s.recentIdeaKeys.removeAt(0)
                return p
            }
        }
        return null
    }

    /** "That's cool!": keep it, and lean toward this kind of idea. */
    fun keep(s: CreatureState, plan: Plan, text: String, now: Long): Idea {
        val idea = Idea(now, plan.move.id, plan.things.flatMap { listOfNotNull(it.word, it.nickname) }, text)
        s.ideas += idea
        while (s.ideas.size > MAX_KEPT) s.ideas.removeAt(0)
        s.ideasKept++
        nudge(s, plan.move, +0.5)
        s.addJournal(JournalEntry(now, plan.things.firstOrNull()?.word, Mood.DELIGHTED.word,
            "Day ${Mind.dayNumber(s, now)}: new idea. $text"))
        return idea
    }

    /** "Not too sure about that one": let it go, and lean away a little. Nothing else changes. */
    fun drop(s: CreatureState, plan: Plan) = nudge(s, plan.move, -0.3)

    private fun nudge(s: CreatureState, move: Move, by: Double) {
        s.moveScores[move.id] = ((s.moveScores[move.id] ?: SCORE_START) + by).coerceIn(SCORE_MIN, SCORE_MAX)
    }

    private fun <T> weighted(items: List<T>, rng: Random, weight: (T) -> Double): T {
        val total = items.sumOf(weight)
        var r = rng.nextDouble() * total
        for (it in items) { r -= weight(it); if (r <= 0) return it }
        return items.last()
    }

    /** The prompt's event line, in the creature's own voice. */
    fun promptLine(p: Plan): String {
        val t = p.things.map { it.phrase }
        return when (p.move) {
            Move.COMMON -> "event: a new idea about what ${t[0]} and ${t[1]} have in common"
            Move.FRIENDS -> "event: a new idea: ${t[0]} and ${t[1]} would be friends, and why"
            Move.HELPS -> "event: a new idea about how ${t[0]} could help ${t[1]}"
            Move.KIND_RULE -> "event: a new idea about my ${p.kinds}, like ${t.joinToString(" and ")}, and what they all share"
            Move.FAVORITE_WHY -> "event: a new idea about why I like ${p.kinds} so much, like ${t[0]}"
            Move.BUILD_ON -> "event: a new idea that adds ${t[0]} to my old idea: ${p.idea?.text}"
        }
    }

    /** Written ideas for when the model's line doesn't pass. Plain and safe, still personal. */
    fun fallback(p: Plan, roll: Int): String {
        val a = p.things.getOrNull(0)?.phrase ?: "this"
        val b = p.things.getOrNull(1)?.phrase ?: "that"
        val options = when (p.move) {
            Move.COMMON -> listOf("I think {a} and {b} have more in common than it looks.", "Maybe {a} and {b} are secret cousins.")
            Move.FRIENDS -> listOf("I think {a} and {b} would be good friends.", "{A} and {b}. Best buddies, I can tell.")
            Move.HELPS -> listOf("Maybe {a} could help {b} on a busy day.", "I bet {a} would look after {b}.")
            Move.KIND_RULE -> listOf("All my {kinds} share something. I'm sure of it.", "I think my {kinds} are a team.")
            Move.FAVORITE_WHY -> listOf("I like {kinds} because they surprise me. Like {a}.", "Maybe I like {kinds} because of {a}.")
            Move.BUILD_ON -> listOf("And {a} fits into my old idea too.", "Hm! {A} belongs in that idea as well.")
        }
        return options[Math.floorMod(roll, options.size)]
            .replace("{A}", a.replaceFirstChar { it.uppercase() }).replace("{a}", a)
            .replace("{b}", b).replace("{kinds}", p.kinds ?: "things")
    }

    // What the creature says after the person decides. No guilt either way, no praise of the person.
    val KEEP = listOf("Ooh. Keeping that one.", "Into my idea pile it goes.", "I'll remember that idea.")
    val DROP = listOf("Okay, back to thinking.", "Fair. That one was a bit wobbly.", "Hm, okay. Another idea later.")
    val SKIP = listOf("I'll keep thinking about it.", "Hm. Maybe later.")
    val NEED_MORE = listOf("I need to see more things first. Then I can think.", "My head is a bit empty. Show me things?")
}
