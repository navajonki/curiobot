package dev.curiobot.brain

/**
 * Builds what the text model sees, and parses what it writes back.
 *
 * Each turn is a fresh conversation: the creature's continuity lives in
 * [CreatureState], not in the model's context. The format and the worked
 * examples are checked against the shipped model file (see ModelLoopTest).
 */
object Prompt {
    data class Spec(
        val system: String,
        val examples: List<Pair<String, String>>,
        val user: String,
        val requiredWord: String?,
        val aboutPerson: Boolean,
        val intents: List<Intent>,
        /** Other words the line may use (see [Event.context]). */
        val context: String = "",
        val selfName: String? = null,
    )

    data class Parsed(val line: String?, val labels: List<String?>, val wellFormed: Boolean)

    fun system(n: Int): String {
        val slots = (1..n).joinToString("\n") { "$it: <label>" }
        return "You write lines for a tiny pocket creature. It is curious, gentle and a little odd. " +
            "Write ONE short line the creature says (under 12 words), about the thing in the event or how it feels. " +
            "If there is a mention line, the creature's line must include that word. " +
            "The creature speaks as I and only talks about what is in the event. It does not ask questions. " +
            "Never describe or praise the person. Never give advice or tell the person to do anything. " +
            "Then write $n short button labels (1 to 4 words each) that match the given intents, in order. " +
            "Use exactly this format:\nSAY: <line>\n$slots"
    }

    val EXAMPLES = listOf(
        "name: Pib\npersonality: shy\nstyle: soft, says hm a lot\nmood: delighted\n" +
            "saw: fern (first time)\nmention: fern\nintents: ask what it thinks | show something new" to
            "SAY: Hm. Tiny green feathers. I like this fern a lot.\n1: What do you think?\n2: Show something new",
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: curious\n" +
            "event: the mug goes with the spoon I saw before\nmention: mug\nintents: ask what it thinks | show something new" to
            "SAY: A mug! It goes with my spoon. Breakfast team.\n1: Tell me more\n2: Find something new",
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: bored\n" +
            "saw: chair (seen 9 times)\nmention: chair\nintents: show something new | remember a day" to
            "SAY: The chair again. I know this chair.\n1: Find something new\n2: Remember a day",
    )

    fun build(t: Traits, mood: Mood, event: Event, intents: List<Intent>): Spec {
        val modelIntents = intents.filter { it.modelLabel }
        val user = buildString {
            append("name: ").append(t.name).append('\n')
            append("personality: ").append(t.temperament).append('\n')
            append("style: ").append(t.style).append('\n')
            append("mood: ").append(mood.word).append('\n')
            append(event.promptLine()).append('\n')
            event.requiredWord?.let { append("mention: ").append(it).append('\n') }
            append("intents: ").append(modelIntents.joinToString(" | ") { it.ask })
        }
        return Spec(system(modelIntents.size), EXAMPLES, user, event.requiredWord, event.aboutPerson, intents,
            event.context, t.name)
    }

    private val SAY = Regex("^\\s*SAY\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val SLOT = Regex("^\\s*(\\d)\\s*[:.)]\\s*(.+?)\\s*$")

    fun parse(raw: String, n: Int): Parsed {
        var line: String? = null
        val labels = arrayOfNulls<String>(n)
        for (l in raw.lines()) {
            if (line == null) SAY.find(l)?.let { line = tidy(clean(it.groupValues[1])); return@let }
            SLOT.find(l)?.let {
                val i = it.groupValues[1].toInt() - 1
                if (i in 0 until n && labels[i] == null) labels[i] = clean(it.groupValues[2])
            }
        }
        return Parsed(line, labels.toList(), line != null && labels.all { it != null })
    }

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
    private val MENU_ECHO = Regex("\\b(show|find) something new\\b|\\bask what it thinks\\b|\\bremember a day\\b|\\bsay hello back\\b|\\bnap time\\b")

    /**
     * Small, safe trims before the checks: drop a sentence that just repeats a
     * button ("I like the spoon! Show something new."), and a trailing musing
     * question when there's a statement before it. Returns null if nothing is left.
     */
    fun tidy(line: String): String? {
        var parts = line.replace(Regex("\\s*:\\s*"), ", ").split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        parts = parts.filterNot { MENU_ECHO.containsMatchIn(it.lowercase()) }
        while (parts.size > 1 && parts.last().endsWith("?")) parts = parts.dropLast(1)
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** Strip quotes, markdown and stray spaces the model sometimes adds. */
    private fun clean(s: String) = s.trim().trim('"', '\'', '*', '`').replace(Regex("\\s+"), " ").trim()
}
