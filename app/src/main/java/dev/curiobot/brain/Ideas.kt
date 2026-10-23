package dev.curiobot.brain

import kotlin.random.Random

/** A thought the creature had and the person chose to keep ("That's cool!"). */
data class Idea(val ts: Long, val move: String, val words: List<String>, val text: String, val hook: String = "",
                /** What "Say more" added, in order. */
                val more: List<String> = emptyList())

/**
 * Reflection: the creature combines things from its past into a new thought.
 *
 * Code does the thinking that matters: it picks what to combine (things it has
 * seen, nicknamed friends, nest kinds, ideas it already kept), how (a [Move]),
 * and the connection itself (a [HOOKS] trait, often borrowed from one thing's
 * kind and applied to the other, which is where the whimsy comes from). The
 * text model only puts that into words, and the person decides
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
        TEAM("team"),                // two things as a team
        KIND_RULE("kind_rule"),      // a guess about a whole kind in the nest
        FAVORITE_WHY("favorite_why"),// why it likes its favorite kind
        BUILD_ON("build_on"),        // adds something new to a kept idea
    }

    /** Something it can think about. [name] is what the line must say. */
    data class Thing(val word: String, val cat: String, val nickname: String?) {
        val name get() = nickname ?: word
        val phrase get() = nickname?.let { "$it the $word" } ?: "the $word"
    }

    /**
     * Traits by kind, written to follow "they both" ("they both go round and round").
     * Short, gentle and safe on purpose: these are the only claims an idea makes.
     */
    val HOOKS = mapOf(
        "animal" to listOf("like naps", "get curious", "like cozy spots", "have their own moods"),
        "bug" to listOf("are busy all day", "like sunny spots", "are tiny explorers"),
        "plant" to listOf("grow a little every day", "love sunlight", "like to stay very still"),
        "nature" to listOf("have been around forever", "change with the weather", "are full of tiny details"),
        "sky" to listOf("change all day", "are far away", "look different at night"),
        "food" to listOf("make someone happy", "come in many colors", "smell nice"),
        "drink" to listOf("keep someone cozy", "like being sipped slowly", "can be warm or cold"),
        "kitchen" to listOf("help at breakfast", "get used every day", "like a tidy shelf"),
        "furniture" to listOf("hold things up", "wait patiently", "like quiet rooms"),
        "home" to listOf("have a special spot", "wait for someone", "keep a home cozy"),
        "clothing" to listOf("keep someone warm", "like going outside", "go on little adventures"),
        "toy" to listOf("like to be played with", "make people smile", "remember old games"),
        "sport" to listOf("love to move", "like teamwork", "like a sunny day"),
        "music" to listOf("make sounds", "like a good rhythm", "can be loud or soft"),
        "art" to listOf("make colors", "like messy tables", "start new pictures"),
        "tech" to listOf("hum quietly", "are full of tiny parts", "need a little power"),
        "tool" to listOf("fix things", "like hard work", "live in a drawer"),
        "vehicle" to listOf("go places", "have wheels", "go round and round"),
        "place" to listOf("have doors", "stay in one spot", "are full of stories"),
    )
    private val HOOKS_ANY = listOf("are a little odd", "have a secret", "like being noticed")

    private fun hookFor(cat: String?, rng: Random) = (HOOKS[cat] ?: HOOKS_ANY).let { it[rng.nextInt(it.size)] }

    data class Plan(val move: Move, val things: List<Thing>, val kinds: String? = null, val idea: Idea? = null,
                    val hook: String = "") {
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
                Move.COMMON, Move.FRIENDS, Move.TEAM -> {
                    // One recent thing plus one from anywhere, across kinds when possible, with a trait
                    // borrowed from either one's kind: a cup that "goes round and round" like a bicycle.
                    val a = things[rng.nextInt(minOf(6, things.size))]
                    val pool = things.filter { it.word != a.word && it.cat != a.cat }
                        .ifEmpty { things.filter { it.word != a.word } }
                    val b = pool[rng.nextInt(pool.size)]
                    Plan(move, listOf(a, b), hook = hookFor(if (rng.nextBoolean()) a.cat else b.cat, rng))
                }
                Move.KIND_RULE -> {
                    val slot = bigKinds[rng.nextInt(bigKinds.size)]
                    val picked = slot.words.shuffled(rng).take(2).mapNotNull { w -> things.firstOrNull { it.word == w } }
                    Plan(move, picked, kinds = Nest.NAMES[slot.kind], hook = hookFor(slot.kind, rng))
                }
                Move.FAVORITE_WHY -> {
                    val inFav = things.filter { it.cat == fav }
                    Plan(move, listOf(inFav[rng.nextInt(inFav.size)]), kinds = Nest.NAMES[fav], hook = hookFor(fav, rng))
                }
                Move.BUILD_ON -> {
                    val idea = s.ideas[s.ideas.size - 1 - rng.nextInt(minOf(5, s.ideas.size))]
                    val fresh = things.filter { it.word !in idea.words && it.name !in idea.words }
                    if (fresh.isEmpty() || idea.hook.isEmpty()) null
                    else Plan(move, listOf(fresh[rng.nextInt(minOf(6, fresh.size))]), idea = idea, hook = idea.hook)
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

    /** "That's cool!": keep it (with anything "Say more" added), and lean toward this kind of idea. */
    fun keep(s: CreatureState, plan: Plan, text: String, now: Long, more: List<String> = emptyList(),
             grown: List<More> = emptyList()): Idea {
        val words = (plan.things.flatMap { listOfNotNull(it.word, it.nickname) } + (plan.idea?.words ?: emptyList()) +
            grown.mapNotNull { it.extra?.word }).distinct()
        val idea = Idea(now, plan.move.id, words, text, plan.hook, more)
        s.ideas += idea
        while (s.ideas.size > MAX_KEPT) s.ideas.removeAt(0)
        s.ideasKept++
        nudge(s, plan.move, +0.5)
        val full = (listOf(text) + more).joinToString(" ").let { if (it.length > 220) it.take(217) + "..." else it }
        s.addJournal(JournalEntry(now, plan.things.firstOrNull()?.word, Mood.DELIGHTED.word,
            "Day ${Mind.dayNumber(s, now)}: new idea. $full"))
        return idea
    }

    /** Asking it to say more is a small sign of interest in this kind of idea. */
    fun interested(s: CreatureState, plan: Plan) = nudge(s, plan.move, +0.2)

    // ---------- "Say more" ----------

    /** How an idea grows. Code picks the direction and its content; the model only phrases it. */
    enum class Grow(val id: String) {
        COULD_EVEN("could_even"),  // borrow a second trait: "the cup could even have wheels"
        STORY("story"),            // a tiny scene: "late at night they go round and round together"
        ONE_MORE("one_more"),      // a third thing it has seen joins the idea
    }

    data class More(val grow: Grow, val content: String, val required: String, val extra: Thing? = null)

    const val MAX_MORE = 2
    val SETTINGS = listOf("on a rainy day", "at breakfast", "late at night", "on a long trip", "in a quiet room",
        "on a sunny morning")

    /** "are far away" to "be far away", for after "could even". */
    fun base(hook: String) = if (hook.startsWith("are ")) "be " + hook.removePrefix("are ") else hook

    /**
     * Pick the next step for an idea, avoiding the directions and extra things
     * already used on it. Returns null only if there is truly nothing to add.
     */
    fun grow(s: CreatureState, plan: Plan, done: List<More>, nicknames: Map<String, String>, rng: Random): More? {
        val a = plan.things.firstOrNull() ?: return null
        val b = plan.things.getOrNull(1)
        val used = plan.things.map { it.word } + done.mapNotNull { it.extra?.word } + (plan.idea?.words ?: emptyList())
        val extras = material(s, nicknames).filter { it.word !in used }
        val usedHooks = setOf(plan.hook)
        val otherHooks = (HOOKS[b?.cat ?: a.cat].orEmpty() + HOOKS.values.flatten().shuffled(rng).take(3))
            .filter { it !in usedHooks && !it.startsWith("can ") }
        val options = Grow.values().filter { g ->
            g !in done.map { it.grow } && when (g) {
                Grow.COULD_EVEN -> otherHooks.isNotEmpty()
                Grow.ONE_MORE -> extras.isNotEmpty() && plan.hook.isNotEmpty()
                Grow.STORY -> plan.hook.isNotEmpty()
            }
        }
        if (options.isEmpty()) return null
        return when (options[rng.nextInt(options.size)]) {
            Grow.COULD_EVEN -> {
                val h = otherHooks.first()
                More(Grow.COULD_EVEN, "maybe ${a.phrase} could even ${base(h)}", a.name)
            }
            Grow.STORY -> {
                val setting = SETTINGS[rng.nextInt(SETTINGS.size)]
                val scene = if (b != null) "${a.phrase} and ${b.phrase} ${plan.hook} together $setting"
                    else "${a.phrase} ${singular(plan.hook)} $setting"
                More(Grow.STORY, "a tiny story: $scene", a.name)
            }
            Grow.ONE_MORE -> {
                val c = extras[rng.nextInt(minOf(6, extras.size))]
                More(Grow.ONE_MORE, "${c.phrase} joins in, because it ${singular(plan.hook)} too", c.name, extra = c)
            }
        }
    }

    fun morePromptLine(bud: String, m: More) = "event: saying more about my idea (${bud.trimEnd('.', '!')}): ${m.content}"

    /** Written version of a "Say more" step, for when the model's line doesn't pass. */
    fun moreFallback(m: More, roll: Int): String {
        val c = m.content.replaceFirstChar { it.uppercase() }
        return when (m.grow) {
            Grow.COULD_EVEN -> listOf("$c. I like that.", "Ooh. $c.")
            Grow.STORY -> listOf(c.removePrefix("A tiny story: ").replaceFirstChar { it.uppercase() } + ". The end.",
                "I can see it. " + c.removePrefix("A tiny story: ").replaceFirstChar { it.uppercase() } + ".")
            Grow.ONE_MORE -> listOf("And ${m.content}!", "$c!")
        }[Math.floorMod(roll, 2)]
    }

    /** Reflect on one nest kind (from its page): a rule about the kind, or one of its things with another thing. */
    fun planForKind(s: CreatureState, kind: String, nicknames: Map<String, String>, rng: Random): Plan? {
        val things = material(s, nicknames)
        val mine = things.filter { it.cat == kind }
        val others = things.filter { it.cat != kind }
        val p = when {
            mine.size >= 2 -> Plan(Move.KIND_RULE, mine.shuffled(rng).take(2), kinds = Nest.NAMES[kind], hook = hookFor(kind, rng))
            mine.size == 1 && others.isNotEmpty() -> Plan(listOf(Move.COMMON, Move.FRIENDS, Move.TEAM)[rng.nextInt(3)],
                listOf(mine[0], others[rng.nextInt(minOf(6, others.size))]), hook = hookFor(kind, rng))
            else -> null
        } ?: return null
        s.recentIdeaKeys += p.key
        while (s.recentIdeaKeys.size > RECENT_KEYS) s.recentIdeaKeys.removeAt(0)
        return p
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

    /** The prompt's event line, in the creature's own voice. Code already chose the connection. */
    fun promptLine(p: Plan): String {
        val t = p.things.map { it.phrase }
        return when (p.move) {
            Move.COMMON -> "event: a new idea: ${t[0]} and ${t[1]} both ${p.hook}"
            Move.FRIENDS -> "event: a new idea: ${t[0]} and ${t[1]} would be friends, since they both ${p.hook}"
            Move.TEAM -> "event: a new idea: ${t[0]} and ${t[1]} would make a good team, since they both ${p.hook}"
            Move.KIND_RULE -> "event: a new idea: all my ${p.kinds}, like ${t.joinToString(" and ")}, ${p.hook}"
            Move.FAVORITE_WHY -> "event: a new idea: maybe I like ${p.kinds} because they ${p.hook}, like ${t[0]}"
            Move.BUILD_ON -> "event: a new idea: ${t[0]} fits my old idea too, it also ${singular(p.hook)}"
        }
    }

    /** "go round and round" to "goes round and round", for one thing instead of two. */
    fun singular(hook: String): String {
        val (verb, rest) = hook.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val v = when {
            verb == "are" -> "is"
            verb == "have" -> "has"
            verb == "can" -> "can"
            verb.endsWith("ch") || verb.endsWith("sh") || verb.endsWith("s") || verb.endsWith("x") ||
                verb.endsWith("z") || verb.endsWith("o") -> verb + "es"
            verb.endsWith("y") && verb.length > 2 && verb[verb.length - 2] !in "aeiou" -> verb.dropLast(1) + "ies"
            else -> verb + "s"
        }
        return if (rest.isEmpty()) v else "$v $rest"
    }

    /** Written ideas for when the model's line doesn't pass. Same connection, plainer words. */
    fun fallback(p: Plan, roll: Int): String {
        val a = p.things.getOrNull(0)?.phrase ?: "this"
        val b = p.things.getOrNull(1)?.phrase ?: "that"
        val options = when (p.move) {
            Move.COMMON -> listOf("I think {a} and {b} both {hook}.", "Maybe {a} and {b} are cousins. They both {hook}.")
            Move.FRIENDS -> listOf("I think {a} and {b} would be friends. They both {hook}.", "{A} and {b}, best buddies. They both {hook}.")
            Move.TEAM -> listOf("Maybe {a} and {b} are a team. They both {hook}.", "I think {a} and {b} would team up. They both {hook}.")
            Move.KIND_RULE -> listOf("I think all my {kinds} {hook}. Like {a} and {b}.", "Maybe all {kinds} {hook}. {A} does.")
            Move.FAVORITE_WHY -> listOf("Maybe I like {kinds} because they {hook}. Like {a}.", "I think I like {kinds} because they {hook}.")
            Move.BUILD_ON -> listOf("{A} fits my old idea too. It also {one}.", "Oh! {A} also {one}.")
        }
        return options[Math.floorMod(roll, options.size)]
            .replace("{A}", a.replaceFirstChar { it.uppercase() }).replace("{a}", a)
            .replace("{b}", b).replace("{kinds}", p.kinds ?: "things")
            .replace("{one}", singular(p.hook)).replace("{hook}", p.hook)
    }

    // What the creature says after the person decides. No guilt either way, no praise of the person.
    val KEEP = listOf("Ooh. Keeping that one.", "Into my idea pile it goes.", "I'll remember that idea.")
    val DROP = listOf("Okay, back to thinking.", "Fair. That one was a bit wobbly.", "Hm, okay. Another idea later.")
    val SKIP = listOf("I'll keep thinking about it.", "Hm. Maybe later.")
    val NO_MORE = listOf("That's all I've got on that one.", "Hm. That's the whole idea, I think.")
    val NEED_MORE = listOf("I need to see more things first. Then I can think.", "My head is a bit empty. Show me things?")
}
