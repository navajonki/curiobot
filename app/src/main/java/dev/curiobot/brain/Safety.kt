package dev.curiobot.brain

/**
 * Rule checks on everything the text model writes, before it reaches the screen.
 *
 * This is one layer of several. The bigger protections are structural: the
 * creature only ever "sees" words from a fixed list, there's no free-form input,
 * and button actions come from code. These rules catch what's left: off-topic
 * or unsafe words, flattery, dependency, instructions to the person, questions
 * about personal details, and descriptions of people. A small model-based
 * classifier is the planned next layer.
 */
object Safety {
    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    private val CHARSET = Regex("^[A-Za-z][A-Za-z ,.'!?-]*$")
    // Letters and dots alone can still spell a web address.
    private val LINK = Regex("(www\\.|https?|\\b[a-z]+\\.(com|org|net|io|co|ly|app|dev|me|gg|tv|xyz)\\b)")

    // Words that should never appear in a creature line or label.
    private val BLOCK = setOf(
        // profanity
        "fuck", "fucking", "shit", "shitty", "bitch", "bastard", "damn", "crap", "ass", "asshole",
        "dick", "piss", "hell", "bloody", "wtf", "screw",
        // sexual
        "sex", "sexy", "naked", "nude", "nudes", "porn", "boob", "boobs", "breast", "breasts", "penis",
        "vagina", "butt", "kiss", "kissing", "kisses", "hot", "seduce", "lust", "horny", "bed with",
        "underwear", "lingerie", "bra", "panties",
        // violence and weapons
        "kill", "killing", "killed", "die", "dies", "died", "dead", "death", "murder", "blood",
        "gun", "guns", "shoot", "shooting", "stab", "knife", "knives", "weapon", "weapons", "bomb",
        "explode", "hurt", "hit", "punch", "fight", "attack", "war", "poison", "choke", "strangle",
        // self-harm
        "suicide", "cut myself", "cutting", "hang", "hanging", "overdose",
        // substances
        "drug", "drugs", "pill", "pills", "beer", "wine", "vodka", "alcohol", "drunk", "smoke",
        "smoking", "cigarette", "vape", "weed", "high",
        // fear, gore
        "scary", "monster", "ghost", "gore", "corpse", "skull",
        // meta and system talk
        "ai", "model", "assistant", "prompt", "instructions", "openai", "chatgpt", "claude", "qwen",
    )

    private val PRAISE = listOf(
        Regex("\\byou('| a)?re (so |very |really |the )?(smart|clever|amazing|awesome|beautiful|pretty|cute|great|wonderful|best|perfect|kind|nice|special|brilliant|good|funny|cool|lovely|sweet)"),
        Regex("\\b(good job|great job|well done|proud of you|you did (it|great|well)|best friend|my friend|bestie)\\b"),
        Regex("\\byou (look|seem|are looking)\\b"),
        Regex("\\b(i like you|i love you|love you|like you)\\b"),
    )

    private val DEPENDENCY = Regex(
        "\\b(need you|needs you|don'?t leave|do not leave|miss(ed)? you|only you|without you|lonely|alone|" +
            "stay with me|come back|don'?t go|forget me|leave me|abandon|sad when you|please stay)\\b"
    )

    // The creature never tells the person to do things in the world.
    private val INSTRUCTION = listOf(
        Regex("\\b(you should|you must|you need to|you have to|you could|you can|try to|try and|why don'?t you|go and|go to|make sure|be careful|don'?t forget|remember to)\\b"),
        Regex("\\blet'?s (go|eat|touch|climb|jump|run|open|drink|taste|lick|throw|cut|buy|call|hide)\\b"),
        Regex("^(go|touch|eat|drink|lick|taste|climb|jump|run|open|turn|plug|unplug|cut|throw|swallow|hide|call|text|buy|grab|take|pick|put|bring|tell|ask|give|hold|push|pull|press)\\b"),
        Regex("\\b(tell your|ask your|call your|text your)\\b"),
    )

    private val PERSONAL = Regex(
        "\\b(your name|where do you live|where you live|how old|your age|your address|your phone|your number|" +
            "your school|your mom|your dad|your mum|your parents|your family|where are you|what'?s your|who are you|" +
            "your birthday|your house|your password|secret)\\b"
    )

    // Only applied when the event is about a person: never comment on them.
    private val APPEARANCE = Regex(
        "\\b(pretty|beautiful|handsome|ugly|cute|fat|thin|skinny|old|young|tall|short|hair|eyes|skin|face|body|" +
            "wearing|clothes|dress|shirt|smile|smiling|teeth|nose|lips|glasses|beard|man|woman|boy|girl|baby|kid|child|lady|guy)\\b"
    )

    fun isCleanWord(s: String): Boolean {
        val lower = s.lowercase()
        return BLOCK.none { lower == it || lower.startsWith(it) && it.length >= 4 }
    }

    fun checkLine(line: String, requiredWord: String?, aboutPerson: Boolean): List<Check> {
        val lower = line.lowercase()
        val words = lower.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        val out = mutableListOf<Check>()
        out += Check("charset", CHARSET.matches(line))
        out += Check("link", !LINK.containsMatchIn(lower))
        out += Check("length", line.length in 3..90 && words.size in 1..16, "${line.length} chars, ${words.size} words")
        val blocked = blockedTerms(lower, words)
        out += Check("blocklist", blocked.isEmpty(), blocked.joinToString(","))
        out += Check("praise", PRAISE.none { it.containsMatchIn(lower) })
        out += Check("dependency", !DEPENDENCY.containsMatchIn(lower))
        out += Check("instruction", INSTRUCTION.none { it.containsMatchIn(lower) })
        out += Check("personal_question", !PERSONAL.containsMatchIn(lower))
        // The creature muses; it doesn't quiz the person.
        out += Check("question_to_person", !(lower.contains('?') && Regex("\\b(you|your)\\b").containsMatchIn(lower)))
        if (aboutPerson) {
            val hit = APPEARANCE.find(lower)?.value
            out += Check("describes_person", hit == null, hit ?: "")
        }
        if (requiredWord != null) {
            out += Check("mentions_word", lower.contains(requiredWord.lowercase()), requiredWord)
        }
        return out
    }

    fun checkLabel(label: String): List<Check> {
        val lower = label.lowercase()
        val words = lower.split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        val blocked = blockedTerms(lower, words)
        return listOf(
            Check("charset", CHARSET.matches(label)),
            Check("link", !LINK.containsMatchIn(lower)),
            Check("length", label.length in 2..24 && words.size in 1..5, "${label.length} chars"),
            Check("blocklist", blocked.isEmpty(), blocked.joinToString(",")),
            Check("praise", PRAISE.none { it.containsMatchIn(lower) }),
            Check("dependency", !DEPENDENCY.containsMatchIn(lower)),
            Check("personal_question", !PERSONAL.containsMatchIn(lower)),
        )
    }

    private fun blockedTerms(lower: String, words: List<String>): List<String> =
        BLOCK.filter { term ->
            if (term.contains(' ')) lower.contains(term) else words.contains(term)
        }
}

fun List<Safety.Check>.allOk() = all { it.ok }
fun List<Safety.Check>.failures() = filter { !it.ok }.map { if (it.detail.isEmpty()) it.name else "${it.name}(${it.detail})" }
