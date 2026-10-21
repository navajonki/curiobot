package dev.curiobot.brain

import java.util.SplittableRandom

/**
 * Everything about a creature that comes from its seed. Two devices with the
 * same seed hatch the same creature; they diverge only through what they're shown.
 */
data class Traits(
    val seed: Long,
    val name: String,
    val temperament: String,
    /** How it phrases things on screen. */
    val style: String,
    val loves: List<String>,
    val dislikes: String,
    /** How fast it gets hungry for new things, 0.4..1.0. */
    val curiosity: Double,
    /** Shy creatures warm up to people more slowly, 0..1. */
    val shyness: Double,
) {
    /** One line for the text model. */
    fun describe(): String =
        "$temperament, loves ${loves.joinToString(" and ") { plural(it) }}, not keen on ${plural(dislikes)}"

    private fun plural(cat: String) = CATEGORY_WORDS[cat] ?: cat
}

/** How each vocabulary category reads in a sentence. */
val CATEGORY_WORDS = mapOf(
    "animal" to "animals", "bug" to "bugs", "plant" to "plants", "food" to "food",
    "drink" to "drinks", "kitchen" to "kitchen things", "furniture" to "furniture",
    "home" to "little household things", "clothing" to "clothes", "toy" to "toys",
    "tool" to "tools", "vehicle" to "vehicles", "nature" to "rocks and water and outdoors",
    "sky" to "the sky", "place" to "buildings and places", "tech" to "gadgets",
    "music" to "music things", "sport" to "sports things", "art" to "art supplies",
)

object Genome {
    private val TEMPERAMENTS = listOf("shy", "bold", "calm", "playful", "dreamy", "fussy")
    private val STYLES = mapOf(
        "shy" to listOf("soft, says hm a lot", "whispery and careful"),
        "bold" to listOf("blunt and funny", "excited, short words"),
        "calm" to listOf("slow and dreamy", "soft, says hm a lot"),
        "playful" to listOf("bouncy and bright", "excited, short words"),
        "dreamy" to listOf("slow and dreamy", "whispery and careful"),
        "fussy" to listOf("blunt and funny", "soft, says hm a lot"),
    )
    private val LOVABLE = listOf(
        "animal", "bug", "plant", "food", "toy", "vehicle", "nature", "sky", "music",
        "art", "kitchen", "tech", "sport", "clothing", "place", "home", "drink",
    )
    private val ONSETS = listOf("P", "M", "Z", "Qu", "F", "B", "N", "T", "K", "L", "Sn", "Gl", "Pl", "Dr", "W", "Y", "V", "Fl", "Bl", "J")
    private val VOWELS = listOf("i", "o", "u", "a", "e", "oo", "ee", "y")
    private val CODAS = listOf("b", "tt", "p", "k", "n", "m", "x", "zz", "ff", "l", "sh", "g", "d", "mp")

    fun traitsFor(seed: Long): Traits {
        val r = SplittableRandom(seed)
        val temperament = TEMPERAMENTS[r.nextInt(TEMPERAMENTS.size)]
        val styles = STYLES.getValue(temperament)
        val style = styles[r.nextInt(styles.size)]
        val pool = LOVABLE.toMutableList()
        val loves = List(2) { pool.removeAt(r.nextInt(pool.size)) }
        val dislikes = pool[r.nextInt(pool.size)]
        val curiosity = 0.4 + 0.6 * r.nextDouble()
        val shyness = when (temperament) {
            "shy" -> 0.7 + 0.3 * r.nextDouble()
            "bold", "playful" -> 0.2 * r.nextDouble()
            else -> 0.2 + 0.4 * r.nextDouble()
        }
        return Traits(seed, nameFor(r), temperament, style, loves, dislikes, curiosity, shyness)
    }

    private fun nameFor(r: SplittableRandom): String {
        repeat(50) {
            val base = ONSETS[r.nextInt(ONSETS.size)] + VOWELS[r.nextInt(VOWELS.size)] + CODAS[r.nextInt(CODAS.size)]
            val name = if (r.nextInt(4) == 0) base + VOWELS[r.nextInt(VOWELS.size)] else base
            if (name.length in 3..7 && Safety.isCleanWord(name)) return name
        }
        return "Pib"
    }
}
