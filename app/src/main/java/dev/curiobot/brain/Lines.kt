package dev.curiobot.brain

/**
 * Hand-written lines. Used where code speaks for the creature on purpose
 * (tripwires, blank photos, games, sleep) and as the safe fallback whenever the
 * text model's line fails a check twice.
 */
object Lines {
    fun pick(options: List<String>, roll: Int, word: String? = null, name: String? = null, label: String? = null): String {
        val w = word ?: "thing"
        val s = options[Math.floorMod(roll, options.size)]
            .replace("A {word}", withArticle(w).replaceFirstChar { it.uppercase() })
            .replace("a {word}", withArticle(w))
        return s.replace("{word}", w)
            .replace("{Word}", (word ?: "thing").replaceFirstChar { it.uppercase() })
            .replace("{name}", name ?: "")
            .replace("{label}", label ?: "something new")
            .replace("{Label}", (label ?: "something new").replaceFirstChar { it.uppercase() })
    }

    val HATCH = listOf("Hi. I'm {name}. Show me things?", "Oh! Hello. I'm {name}. What's out there?")

    val FALLBACK = mapOf(
        Mood.DELIGHTED to listOf("{Word}! Oh, I like it.", "A {word}. Lovely.", "Ooh. A {word}. Yes."),
        Mood.CURIOUS to listOf("Hm. A {word}. Interesting.", "So that is a {word}.", "A {word}. I want to know more."),
        Mood.CONTENT to listOf("A {word}. Nice.", "{Word}. I like it here."),
        Mood.MEH to listOf("A {word}. Hm.", "{Word}. Sure."),
        Mood.BORED to listOf("The {word} again. I know this {word}.", "{Word}. Seen it."),
        Mood.WARY to listOf("A {word}. Not for me.", "Hm. A {word}. I'll watch from here."),
        Mood.FRIENDLY to listOf("Oh! Hi there.", "A person! Hello.", "Hi. I see you."),
        Mood.SLEEPY to listOf("So sleepy.", "Yawn. Eyes heavy."),
        Mood.PUZZLED to listOf("Hm. Fuzzy.", "I'm not sure what that is."),
        Mood.QUIET to listOf("Hm."),
    )

    // Written-only events: people, hellos, waking up.
    val SAW_PERSON = listOf("Oh! Hi there.", "A person! Hello.", "Hi. I see you.", "Oh, hello!")
    val HELLO = listOf("Hello, hello!", "Hi! Waving back.", "Hello to you too.", "Hi hi.")

    // What things remind it of, by kind. Code picks one; the model only voices it.
    val THOUGHTS = mapOf(
        "food" to listOf("a picnic", "a cozy lunch", "a snack at the park", "a birthday"),
        "drink" to listOf("a cool morning", "a sunny porch", "a slow afternoon"),
        "kitchen" to listOf("warm soup smells", "a busy morning", "cookies baking"),
        "home" to listOf("a quiet afternoon", "rainy days indoors", "a tidy shelf"),
        "furniture" to listOf("a long nap", "a lazy Sunday", "a reading nook"),
        "clothing" to listOf("a chilly walk", "going outside", "a windy day"),
        "toy" to listOf("a game of pretend", "a rainy afternoon", "lots of giggles"),
        "animal" to listOf("a sunny meadow", "a snoozy afternoon", "a trip to the zoo"),
        "bug" to listOf("a summer garden", "tiny footsteps", "a warm breeze"),
        "plant" to listOf("a forest walk", "spring rain", "a sunny window"),
        "nature" to listOf("a long hike", "the seaside", "cool fresh air"),
        "sky" to listOf("drifting clouds", "a starry night", "a big wide world"),
        "place" to listOf("a long trip", "a busy town", "an adventure"),
        "vehicle" to listOf("a road trip", "zooming fast", "going somewhere new"),
        "tech" to listOf("little beeps", "blinking lights", "a busy desk"),
        "tool" to listOf("building a birdhouse", "fixing things", "a busy workshop"),
        "music" to listOf("a happy tune", "dancing around", "a quiet hum"),
        "sport" to listOf("a sunny game day", "running around", "cheering"),
        "art" to listOf("a rainy art day", "bright colors", "a messy table"),
    )
    val THOUGHTS_ANY = listOf("a dream I had", "something far away", "a little puzzle")
    val ABOUT = listOf("The {word} reminds me of {label}.", "Hm. {Word}. Like {label}.", "{Word}... it makes me think of {label}.")

    // The nest and the links between things ({label} is the kind, or the other thing).
    val NEW_KIND = listOf("A {word}! My first {label}. Into the nest.", "Ooh, {label}! The {word} gets a new spot in my nest.")
    val FAVORITE = listOf("{Word}! I think {label} are my favorite now.", "Another one! {Label} might be my favorite.")
    val KIND_GREW = listOf("The {word}! My {label} spot is growing.", "A {word}. More {label} for my nest.")
    val GOES_WITH = listOf("A {word}. It goes with the {label}.", "Ooh, a {word}. Like the {label} from before.")
    val FOUND = listOf("Found it! A {word}!", "A {word}! That's the hunt done.")
    val WISH_CAME_TRUE = listOf("A {word}! My wish came true!", "Oh! A {word}. Just what I wished for.")
    val NAMED = listOf("I'll call you {label}, little {word}.", "This {word} is {label} now.")
    val TAUGHT = listOf("Oh, a {word}! I'll remember that.", "A {word}. Got it. Thank you.")
    val TEACH_CANCEL = listOf("Okay. I'll keep calling it a {word}.", "Never mind, then. Still a {word}.")
    val TEACH_CANCEL_UNSURE = listOf("Okay. Maybe another time.", "That's okay. It's a mystery for now.")

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
    val KIND_EMPTY = listOf("No {label} yet. I wonder what they're like.", "An empty spot for {label}. Someday!")
    val UNNAMED = listOf("No word for it yet. I'll remember how it looks.", "A thing with no name. I like it anyway.", "Hm. A mystery. I'll keep it.")
    val WOKE_EARLY = listOf("Mm? Oh. Hi. Still a bit sleepy.", "Huh? I'm up. Mostly.", "Yawn. Okay, okay. I'm here.")
}
