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
        /** All words the line must include (more than one for ideas). */
        val requiredWords: List<String> = listOfNotNull(requiredWord),
        /** Ideas may run a little longer. */
        val longLine: Boolean = false,
    )

    data class Parsed(val line: String?, val labels: List<String?>, val wellFormed: Boolean)

    fun system(n: Int): String {
        val slots = (1..n).joinToString("\n") { "$it: <label>" }
        return "You write lines for a tiny pocket creature. It is curious, gentle and a little odd. " +
            "Write ONE short line the creature says (under 12 words), about the thing in the event or how it feels. " +
            "If there is a mention line, the creature's line must include those words. " +
            "The creature speaks as I and only talks about what is in the event. It does not ask questions. " +
            "For a new idea, say that idea as a playful guess that starts with Maybe or I think. " +
            "When saying more about an idea, add the new part in a fun way. " +
            "For story time, tell that part of the story in a lively way. " +
            "For a dream, tell that part of the dream softly, like talking in its sleep. " +
            "Never describe or praise the person. Never give advice or tell the person to do anything. " +
            (if (n > 0) "Then write $n short button labels (1 to 4 words each) that match the given intents, in order. " else "") +
            "Use exactly this format:\nSAY: <line>" + (if (n > 0) "\n$slots" else "")
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

    /** Ideas get their own second example, since a 0.6B model copies the shape it's shown. */
    val IDEA_EXAMPLE =
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: curious\n" +
            "event: a new idea: the sock and the kite both love windy days\nmention: sock, kite" to
            "SAY: I think the sock and the kite are cousins. They both love windy days!"

    val MORE_EXAMPLE =
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: delighted\n" +
            "event: saying more about my idea (I think the sock and the kite are cousins): maybe the sock could even fly\nmention: sock" to
            "SAY: Maybe one day the sock floats up high and waves at the kite!"

    val STORY_EXAMPLE =
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: delighted\n" +
            "event: story time, a new adventure: the sock and the kite set off for the windy hill to find a lost button\nmention: sock, kite" to
            "SAY: Once upon a time, the sock and the kite set off for the windy hill. Somewhere up there, a lost button waited!"

    val DREAM_EXAMPLE =
        "name: Zub\npersonality: bold\nstyle: blunt and funny\nmood: sleepy\n" +
            "event: in my dream, the sock and the kite go ice skating on a frozen puddle\nmention: sock, kite, skating" to
            "SAY: Zzz... the sock and the kite go skating on a frozen puddle... wheee..."

    fun build(t: Traits, mood: Mood, event: Event, intents: List<Intent>): Spec {
        val modelIntents = intents.filter { it.modelLabel }
        val user = buildString {
            append("name: ").append(t.name).append('\n')
            append("personality: ").append(t.temperament).append('\n')
            append("style: ").append(t.style).append('\n')
            append("mood: ").append(mood.word).append('\n')
            append(event.promptLine()).append('\n')
            if (event.requiredWords.isNotEmpty()) append("mention: ").append(event.requiredWords.joinToString(", ")).append('\n')
            if (modelIntents.isNotEmpty()) append("intents: ").append(modelIntents.joinToString(" | ") { it.ask })
        }.trimEnd()
        val examples = when (event) {
            is Event.Thought -> listOf(EXAMPLES[0], IDEA_EXAMPLE)
            is Event.MoreThought -> listOf(IDEA_EXAMPLE, MORE_EXAMPLE)
            is Event.StoryBeat -> listOf(EXAMPLES[0], STORY_EXAMPLE)
            is Event.DreamBeat -> listOf(EXAMPLES[0], DREAM_EXAMPLE)
            else -> EXAMPLES
        }
        return Spec(system(modelIntents.size), examples, user, event.requiredWord, event.aboutPerson, intents,
            event.context, t.name, event.requiredWords,
            longLine = event is Event.Thought || event is Event.MoreThought || event is Event.StoryBeat || event is Event.DreamBeat)
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
        val plain = line.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
            .replace('\u2019', '\'').replace('\u2018', '\'').replace("\u2026", "...")
            // stage directions like "(softly)" or "*yawns*"
            .replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*(?<!\\*)\\*[a-z ]+\\*(?!\\*)"), "").trim()
        var parts = plain.replace(Regex("\\s*[:\u2014\u2013]\\s*"), ", ").split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        parts = parts.filterNot { MENU_ECHO.containsMatchIn(it.lowercase()) }
        while (parts.size > 1 && parts.last().endsWith("?")) parts = parts.dropLast(1)
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** Strip quotes, markdown and stray spaces the model sometimes adds. */
    private fun clean(s: String) = s.trim().trim('"', '\'', '*', '`').replace(Regex("\\s+"), " ").trim()
}
