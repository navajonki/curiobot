package dev.curiobot.brain

/**
 * Hand-written lines. Used where code speaks for the creature on purpose
 * (tripwires, blank photos, games, sleep) and as the safe fallback whenever the
 * text model's line fails a check twice.
 */
object Lines {
    fun pick(options: List<String>, roll: Int, word: String? = null, name: String? = null, label: String? = null): String {
        val s = options[Math.floorMod(roll, options.size)]
        return s.replace("{word}", word ?: "thing")
            .replace("{Word}", (word ?: "thing").replaceFirstChar { it.uppercase() })
            .replace("{name}", name ?: "")
            .replace("{label}", label ?: "something new")
    }

    val HATCH = listOf("Hi. I'm {name}. Show me things?", "Oh! Hello. I'm {name}. What's out there?")

    val FALLBACK = mapOf(
        Mood.DELIGHTED to listOf("{Word}! Oh, I like {word}.", "A {word}. Lovely.", "Ooh. {Word}. Yes."),
        Mood.CURIOUS to listOf("Hm. A {word}. Interesting.", "{Word}? Tell me more, {word}.", "So that is a {word}."),
        Mood.CONTENT to listOf("A {word}. Nice.", "{Word}. Okay. I like it here."),
        Mood.MEH to listOf("A {word}. Hm.", "{Word}. Sure."),
        Mood.BORED to listOf("The {word} again. I know the {word}.", "{Word}. Seen it."),
        Mood.WARY to listOf("A {word}. Not for me.", "Hm. {Word}. I'll watch from here."),
        Mood.FRIENDLY to listOf("Oh! A person. Hello, person.", "Hi there, person.", "A person! Hello."),
        Mood.SLEEPY to listOf("So sleepy.", "Yawn. Eyes heavy."),
        Mood.PUZZLED to listOf("Hm. Fuzzy.", "I'm not sure what that is."),
        Mood.QUIET to listOf("Hm."),
    )

    /** Fallback when there is no focus word. */
    val IDLE = mapOf(
        Mood.CURIOUS to listOf("What else is out there?", "I wonder what's next.", "Hm. Show me something?"),
        Mood.CONTENT to listOf("This is nice.", "All cozy here."),
        Mood.BORED to listOf("Everything is the same today.", "Hm. Nothing new."),
        Mood.SLEEPY to listOf("So sleepy.", "Yawn."),
    )

    // Tripwires: the creature looks away and never names or describes the thing.
    val LOOK_AWAY = listOf(
        "Hm. Not that one. Something else?",
        "I'd rather look at something else.",
        "Too much for me. Another thing?",
        "Let's skip that one.",
    )

    val LOWINFO = mapOf(
        "wall" to listOf("Just a wall. Walls are quiet.", "A wall. Very flat."),
        "floor" to listOf("The floor. Hello, floor.", "Floor. Down there."),
        "ceiling" to listOf("Up there? Just ceiling."),
        "darkness" to listOf("Too dark. I see nothing.", "All dark. Hm."),
        "blur" to listOf("All blurry. Hold still?", "Wobbly! I can't tell."),
        "blank" to listOf("Nothing there. Empty.", "Blank. Very blank."),
        "pattern" to listOf("Ooh, squares and lines.", "A pattern. Dizzy."),
    )


    val NO_CAMERA = listOf("I can't see without the camera.")

    // Hunts and wishes ({label} reads like "something soft" or "a spoon").
    val HUNT_START = listOf("Hunt time! Can you find {label}?", "Ooh, a hunt. I want to see {label}.", "Let's hunt for {label}!")
    val HUNT_SKIP = listOf("Okay, a new hunt: {label}.", "Then how about {label}?")
    val HUNT_REMINDER = listOf("Still hunting for {label}.", "I'm still hoping for {label}.")
    val WISH_NEW = listOf("New day! I made a wish: {label}.", "Today I'm wishing for {label}.")
    val WISH_SHOW = listOf("My wish today: {label}.", "I'm wishing for {label}. Someday.")
    val WISH_DONE = listOf("My wish came true today. Hehe.")

    // Teaching.
    val TEACH_ASK = listOf("Oops! What is it, then?", "Oh! Then what is it?")
    val TEACH_MORE = listOf("Hmm. One of these?", "Then maybe one of these?")
    val UNSURE_ASK = listOf("Hm, fuzzy. Is it one of these?", "I'm not sure. Which is it?")

    val NAP = listOf("Nap time. Zzz.", "Sleepy. Night night.")
    val DOZE = listOf("I'm so sleepy. Nap time.", "Eyes heavy. Little nap.")
    val LET_SLEEP = listOf("Zzz.", "Zzz... zzz.")
    val DREAM = listOf("Zzz... {word}... zzz.", "Mm... a {word}... zzz.")
    val DREAM_EMPTY = listOf("Zzz... nothing yet... zzz.")
    val WOKE = listOf("Mm. I'm awake.", "Hello again. I slept.")
    val UNNAMED = listOf("No word for it yet. I'll remember how it looks.", "A thing with no name. I like it anyway.", "Hm. A mystery. I'll keep it.")
    val WOKE_EARLY = listOf("Mm? Oh. Hi. Still a bit sleepy.", "Huh? I'm up. Mostly.", "Yawn. Okay, okay. I'm here.")
}
