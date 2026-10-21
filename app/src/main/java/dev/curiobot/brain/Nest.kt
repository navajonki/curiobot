package dev.curiobot.brain

/**
 * The bigger picture the creature builds from what it is shown: a nest with a
 * token for each kind of thing it has found, which grows as more of that kind
 * turn up, and the links it notices between things of the same kind.
 *
 * Everything here is derived from [CreatureState.seen], so there is no second
 * copy of the truth to drift out of sync.
 */
object Nest {
    /** Display order of the tokens. Matches the vocabulary categories. */
    val KINDS = listOf(
        "animal", "bug", "plant", "nature", "sky", "food", "drink", "kitchen", "furniture", "home",
        "clothing", "toy", "sport", "music", "art", "tech", "tool", "vehicle", "place",
    )

    /** How each kind reads in a sentence and on the nest screen. */
    val NAMES = mapOf(
        "animal" to "animals", "bug" to "bugs", "plant" to "plants", "nature" to "outdoor things",
        "sky" to "sky things", "food" to "foods", "drink" to "drinks", "kitchen" to "kitchen things",
        "furniture" to "furniture", "home" to "household things", "clothing" to "clothes", "toy" to "toys",
        "sport" to "sports things", "music" to "music things", "art" to "art things", "tech" to "gadgets",
        "tool" to "tools", "vehicle" to "vehicles", "place" to "places",
    )

    /** Words of a kind needed for each token level: found, growing, full. */
    val LEVELS = intArrayOf(1, 3, 6)

    data class Slot(val kind: String, val words: List<String>) {
        val level get() = levelFor(words.size)
    }

    fun levelFor(n: Int) = LEVELS.count { n >= it }

    /** Words seen per kind, most recent first. People and unnamed things don't go in the nest. */
    fun slots(s: CreatureState): List<Slot> {
        val byKind = s.seen.entries
            .filter { it.value.cat in KINDS }
            .sortedByDescending { it.value.last }
            .groupBy({ it.value.cat }, { it.key })
        return KINDS.map { Slot(it, byKind[it] ?: emptyList()) }
    }

    fun kindsFound(s: CreatureState) = slots(s).count { it.words.isNotEmpty() }
    fun thingsFound(s: CreatureState) = slots(s).sumOf { it.words.size }

    /** Its favorite kind: the one with the most different things, once there are at least 3. */
    fun favorite(s: CreatureState): String? {
        val best = slots(s).maxByOrNull { it.words.size } ?: return null
        if (best.words.size < LEVELS[1]) return null
        val tied = slots(s).filter { it.words.size == best.words.size }
        return if (tied.size == 1) best.kind else null
    }

    /** A snapshot taken before a sighting, to see what the sighting changed. */
    data class Before(val counts: Map<String, Int>, val favorite: String?)

    fun before(s: CreatureState) = Before(slots(s).associate { it.kind to it.words.size }, favorite(s))

    /**
     * What a new sighting adds to the bigger picture, if anything. Only a word
     * seen for the first time can change the nest. Order matters: a new token
     * beats a new favorite, which beats growth, which beats a plain link.
     */
    fun change(s: CreatureState, before: Before, word: String, cat: String, firstTime: Boolean): Event? {
        if (!firstTime || cat !in KINDS) return null
        val kindWords = NAMES[cat] ?: cat
        val was = before.counts[cat] ?: 0
        val now = slots(s).first { it.kind == cat }
        val fav = favorite(s)
        val other = now.words.firstOrNull { it != word }
        return when {
            was == 0 -> Event.NewKind(word, kindWords)
            fav == cat && before.favorite != cat -> Event.Favorite(word, kindWords)
            levelFor(now.words.size) > levelFor(was) -> Event.KindGrew(word, kindWords, now.words.size)
            other != null -> Event.GoesWith(word, other, kindWords)
            else -> null
        }
    }
}
