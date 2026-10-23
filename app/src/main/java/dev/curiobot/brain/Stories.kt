package dev.curiobot.brain

import kotlin.random.Random

/** A finished adventure, kept in the storybook. */
data class Story(val ts: Long, val title: String, val pages: List<String>)

/**
 * Tiny adventures: a four-beat story starring two things the creature has
 * seen (nicknames included), with a choice between two paths after each beat.
 *
 * Every beat's content is written here in code from small, cozy lists: the
 * places, goals, paths, bumps along the way, twists and endings. The text
 * model only narrates each beat in the creature's voice, so the story can be
 * surprising without ever going somewhere unsafe.
 */
object Stories {
    const val MAX_KEPT = 30

    private val PLACES = listOf("the sunny window", "the kitchen table", "the top of the bookshelf", "the coat closet",
        "the garden gate", "the big blanket fort", "the cozy rug", "the window sill")
    private val GOALS = listOf(
        "find a lost button" to "the lost button", "throw a tiny party" to "the tiny party",
        "watch the sunset" to "the sunset", "build a cozy fort" to "the cozy fort",
        "find the perfect nap spot" to "the perfect nap spot", "make a new friend" to "the new friend",
        "find the shiniest pebble" to "the shiniest pebble",
    )
    private val PATHS = listOf(
        "climb the curtains" to "follow the long rug", "ride a paper boat" to "take the stairs",
        "tiptoe past the clock" to "zoom down the hallway", "hop across the cushions" to "crawl under the chairs",
    )
    // Each bump, twist and ending has a key word the narration must keep, so the line carries the plot.
    private val BUMPS = listOf("a very sleepy {x}" to "sleepy", "a {x} that looks a bit lost" to "lost",
        "a tall wall of books" to "books", "a big puddle" to "puddle", "a gust of wind" to "wind", "a maze of socks" to "socks")
    private val BUMP_CHOICES = listOf("help it out" to "find another way", "ask it to come along" to "wave and keep going",
        "make a plan" to "try something silly")
    private val TWISTS = listOf("{goal} was there all along" to "{key}", "a surprise is waiting" to "surprise",
        "everyone is already there" to "everyone", "it is even better than they hoped" to "better", "they find a tiny door" to "door")
    private val ENDINGS = listOf("stay and celebrate" to "head home together", "share it with everyone" to "save some for later",
        "take a long nap" to "plan the next adventure")
    private val ENDING_KEYS = mapOf("stay and celebrate" to "celebrate", "head home together" to "home",
        "share it with everyone" to "share", "save some for later" to "later", "take a long nap" to "nap",
        "plan the next adventure" to "adventure")

    /** A story in progress: the cast and the beats so far. */
    class Run(
        val hero: Ideas.Thing,
        val friend: Ideas.Thing,
        val place: String,
        val goal: Pair<String, String>,
        private val rng: Random,
        private val extras: List<Ideas.Thing>,
    ) {
        val title = "${hero.name.replaceFirstChar { it.uppercase() }} and ${goal.second}"
        var beat = 0; private set
        /** Narrated lines, in order, for the storybook. */
        val pages = mutableListOf<String>()
        private val paths = PATHS[rng.nextInt(PATHS.size)]
        private val bumpChoices = BUMP_CHOICES[rng.nextInt(BUMP_CHOICES.size)]
        private val endings = ENDINGS[rng.nextInt(ENDINGS.size)]
        private val goalKey = goal.second.substringAfterLast(' ')
        private val bumpPick = BUMPS[rng.nextInt(BUMPS.size)].let { (b, k) ->
            if ("{x}" in b) extras.firstOrNull()?.let { b.replace("{x}", it.word) to k } ?: ("a big puddle" to "puddle") else b to k
        }
        private val bump = bumpPick.first
        private val twistPick = TWISTS[rng.nextInt(TWISTS.size)]
        private val twist = twistPick.first.replace("{goal}", goal.second)
        private val twistKey = twistPick.second.replace("{key}", goalKey)
        private val picks = mutableListOf<String>()

        val cast get() = listOf(hero, friend) + extras.take(1).filter { bump.contains(it.word) }
        fun names() = "${hero.phrase} and ${friend.phrase}"
        val done get() = beat >= 4

        /** What happens in the current beat, written by code. [who] is how the cast is named. */
        fun content(who: String = "they"): String = when (beat) {
            0 -> "${names()} set off for $place to ${goal.first}"
            1 -> "$who ${picks[0]}, and on the way they meet $bump"
            2 -> "$who ${picks[1]}, and when they reach $place, $twist"
            else -> "$who ${picks[2]}"
        }

        /** The word this beat's narration has to keep. */
        fun key(): String = when (beat) {
            0 -> goalKey
            1 -> bumpPick.second
            2 -> twistKey
            else -> ENDING_KEYS[picks[2]] ?: "end"
        }

        /** The two choices after this beat, or none at the end. */
        fun choices(): List<String> = when (beat) {
            0 -> paths.toList()
            1 -> bumpChoices.toList()
            2 -> endings.toList()
            else -> emptyList()
        }

        fun choose(choice: String) { picks += choice; beat++ }
        fun required(): List<String> = (if (beat == 0) listOf(hero.name, friend.name) else listOf(hero.name)) + key()
        fun context() = (listOf(hero.word, friend.word, place, goal.first, goal.second, bump, twist, "story adventure") +
            listOfNotNull(hero.nickname, friend.nickname) + picks + extras.map { it.word }).joinToString(" ")
    }

    /** Two things it has seen become the hero and the friend; a third may turn up along the way. */
    fun start(s: CreatureState, nicknames: Map<String, String>, rng: Random): Run? {
        val things = Ideas.material(s, nicknames)
        if (things.size < 2) return null
        // Named friends make the best heroes.
        val hero = things.firstOrNull { it.nickname != null && rng.nextBoolean() } ?: things[rng.nextInt(minOf(6, things.size))]
        val rest = things.filter { it.word != hero.word }
        val friend = rest[rng.nextInt(minOf(6, rest.size))]
        val extras = rest.filter { it.word != friend.word }.shuffled(rng).take(1)
        return Run(hero, friend, PLACES[rng.nextInt(PLACES.size)], GOALS[rng.nextInt(GOALS.size)], rng, extras)
    }

    // Names instead of "they": a small model loses track of who "they" are.
    fun promptLine(r: Run) = "event: story time, ${if (r.beat == 0) "a new adventure: " else ""}${r.content(r.names())}" +
        if (r.beat >= 3) ", the end" else ""

    /** Written narration for when the model's line doesn't pass. */
    fun fallback(r: Run, roll: Int): String {
        val c = r.content().replaceFirstChar { it.uppercase() }
        // Name the cast instead of "they", so the line still says who the story is about.
        val named = r.content(r.names())
        return when (r.beat) {
            0 -> listOf("Once upon a time, $c.", "Story time! $c.")
            3 -> listOf("And so $named. The end!", "So $named. The end!")
            else -> listOf("Then $named!", "Next, $named!")
        }[Math.floorMod(roll, 2)]
    }

    fun save(s: CreatureState, r: Run, now: Long): Story {
        val story = Story(now, r.title, r.pages.toList())
        s.stories += story
        while (s.stories.size > MAX_KEPT) s.stories.removeAt(0)
        s.addJournal(JournalEntry(now, r.hero.word, Mood.DELIGHTED.word,
            "Day ${Mind.dayNumber(s, now)}: made up a story, \"${r.title}\"."))
        return story
    }

    val START_EMPTY = listOf("I need to know more things for a story. Show me some?")
    val QUIT = listOf("Let's finish that story another time.", "To be continued... maybe.")
    val SAVED = listOf("The end! It's in my storybook now.", "That one goes in the storybook.")
}
