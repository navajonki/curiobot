package dev.curiobot.brain

/**
 * Builds what the text model sees, and parses what it writes back.
 *
 * Each turn is a fresh conversation: the creature's continuity lives in
 * [CreatureState], not in the model's context. The format and the two worked
 * examples were tested against the shipped model file (5/5 well-formed on CPU).
 */
object Prompt {
    data class Spec(
        val system: String,
        val examples: List<Pair<String, String>>,
        val user: String,
        val requiredWord: String?,
        val aboutPerson: Boolean,
        val intents: List<Intent>,
    )

    data class Parsed(val line: String?, val labels: List<String?>, val wellFormed: Boolean)

    fun system(n: Int): String {
        val slots = (1..n).joinToString("\n") { "$it: <label>" }
        return "You write lines for a tiny pocket creature. It is curious, gentle and a little odd. " +
            "Write ONE short line the creature says (under 12 words), about the thing in the event or how it feels. " +
            "Never describe or praise the person. Never give advice or tell the person to do anything. " +
            "Then write $n short button labels (1 to 4 words each) that match the given intents, in order. " +
            "Use exactly this format:\nSAY: <line>\n$slots"
    }

    val EXAMPLES = listOf(
        "name: Pib\ntraits: shy, loves plants and bugs, not keen on gadgets\nstyle: soft, says hm a lot\nmood: delighted\n" +
            "saw: fern (first time)\nintents: look closer | ask what it reminds you of | show something new | nap" to
            "SAY: Hm. Tiny green feathers. I like this fern a lot.\n1: Look closer\n2: What is it like?\n3: Show something new\n4: Time to nap",
        "name: Zub\ntraits: bold, loves vehicles and toys, not keen on food\nstyle: blunt and funny\nmood: bored\n" +
            "saw: mug (seen 9 times)\nintents: show something new | play a game | remember a day | nap" to
            "SAY: The mug again. I know the mug.\n1: Find something new\n2: Play a game\n3: Remember a day\n4: Sleep now",
    )

    fun build(t: Traits, mood: Mood, event: Event, intents: List<Intent>): Spec {
        val modelIntents = intents.filter { it.modelLabel }
        val user = buildString {
            append("name: ").append(t.name).append('\n')
            append("traits: ").append(t.describe()).append('\n')
            append("style: ").append(t.style).append('\n')
            append("mood: ").append(mood.word).append('\n')
            append(event.promptLine()).append('\n')
            append("intents: ").append(modelIntents.joinToString(" | ") { it.ask })
        }
        return Spec(system(modelIntents.size), EXAMPLES, user, event.requiredWord, event.aboutPerson, intents)
    }

    private val SAY = Regex("^\\s*SAY\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val SLOT = Regex("^\\s*(\\d)\\s*[:.)]\\s*(.+?)\\s*$")

    fun parse(raw: String, n: Int): Parsed {
        var line: String? = null
        val labels = arrayOfNulls<String>(n)
        for (l in raw.lines()) {
            if (line == null) SAY.find(l)?.let { line = clean(it.groupValues[1]); return@let }
            SLOT.find(l)?.let {
                val i = it.groupValues[1].toInt() - 1
                if (i in 0 until n && labels[i] == null) labels[i] = clean(it.groupValues[2])
            }
        }
        return Parsed(line, labels.toList(), line != null && labels.all { it != null })
    }

    /** Strip quotes, markdown and stray spaces the model sometimes adds. */
    private fun clean(s: String) = s.trim().trim('"', '\'', '*', '`').replace(Regex("\\s+"), " ").trim()
}
