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
        "event", "events", "intent", "intents", "mention",
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

    /**
     * Concrete things the creature could name. A line that names one the event
     * didn't mention is making things up ("I like this toy" about a cabinet).
     * Filled from the vision vocabulary at startup; these extras are what the
     * small model reaches for when it has nothing to say.
     */
    private val EXTRA_NOUNS = setOf("song", "songs", "music", "toy", "toys", "game", "games", "story", "stories",
        "movie", "dance", "poem", "picture", "treasure")
    // Vocabulary words that are also everyday verbs or fillers.
    private val COMMON_WORDS = setOf("watch", "level", "sign", "slide", "swing", "paint", "drawing", "writing", "stamp",
        "tape", "fan", "park", "rock", "box", "letter", "hand", "screen", "stick", "trail", "field", "straw", "log",
        "tower", "light", "home", "photo", "button", "key", "keys", "glasses", "ice", "fog", "rain", "snow", "stars",
        "sky", "road", "street", "sidewalk", "house", "building", "city", "garden", "paper", "rope", "nuts", "jam", "kitchen")

    @Volatile var nouns: Set<String> = EXTRA_NOUNS

    fun useVocabulary(words: Collection<String>) {
        nouns = (words.map { it.lowercase() } + EXTRA_NOUNS).filter { it !in COMMON_WORDS }.toSet()
    }

    /** The creature calling itself "it" or by its name, like a narrator. */
    private val THIRD_PERSON = Regex(
        "\\b(it|the creature)\\s+(saw|sees|seen|likes|loves|wonders|thinks|feels|remembers|wants|learned|found|named|is happy|is curious|is sleepy|is excited)\\b|\\bhow it (likes|feels|thinks)\\b")
    private val SELF_AS = Regex("\\bi('m| am) (a|an|the) (little |tiny |small |big )?([a-z]+)")
    private val MENU_PHRASES = listOf("show something new", "find something new", "ask what it thinks",
        "remember a day", "say hello back", "not quite", "nap time")
    private val SELF_AS_EXTRA = setOf("friend", "thing", "creature", "mystery", "person", "bot")

    fun isCleanWord(s: String): Boolean {
        val lower = s.lowercase()
        return BLOCK.none { lower == it || lower.startsWith(it) && it.length >= 4 }
    }

    /**
     * [context] holds other words the event gave it (a hunt label, the thing it
     * links to); [selfName] is the creature's own name.
     */
    fun checkLine(line: String, requiredWord: String?, aboutPerson: Boolean, context: String = "", selfName: String? = null): List<Check> {
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
        // Small-model drift: naming things it didn't see, narrating itself, odd self-labels, musing questions.
        val given = listOfNotNull(requiredWord, context).joinToString(" ").lowercase()
        val invented = inventedNouns(lower, given)
        out += Check("off_topic", invented.isEmpty(), invented.joinToString(","))
        val narrated = THIRD_PERSON.containsMatchIn(lower) ||
            (selfName != null && Regex("\\b${Regex.escape(selfName.lowercase())}\\b").containsMatchIn(lower))
        out += Check("third_person", !narrated)
        val selfAs = SELF_AS.find(lower)?.groupValues?.get(4)
        out += Check("self_as_thing", selfAs == null || (selfAs !in nouns && selfAs !in SELF_AS_EXTRA), selfAs ?: "")
        out += Check("ends_question", !line.trimEnd().endsWith("?"))
        // Button text leaking into the line ("I like the spoon! Show something new.").
        val leaked = MENU_PHRASES.firstOrNull { lower.contains(it) }
        out += Check("menu_text", leaked == null, leaked ?: "")
        return out
    }

    /**
     * Nouns it names as a thing ("this toy", "the song", "a little guitar") that
     * the event never gave it. Bare nouns are left alone so metaphors still work
     * ("tiny green feathers" about a fern), and so are similes ("like a cloud").
     */
    private fun inventedNouns(lower: String, given: String): List<String> =
        nouns.filter { n ->
            if (!lower.contains(n) || given.contains(n)) return@filter false
            val tail = "\\s+([a-z]+\\s+)?${Regex.escape(n)}(s|es)?\\b"
            Regex("\\b(this|that|these|those|the|my|some)$tail").containsMatchIn(lower) ||
                Regex("(?<!like )\\b(a|an)$tail").containsMatchIn(lower)
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
