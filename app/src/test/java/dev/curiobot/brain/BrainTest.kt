package dev.curiobot.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenomeTest {
    @Test fun sameSeedSameCreature() {
        assertEquals(Genome.traitsFor(42), Genome.traitsFor(42))
    }

    @Test fun namesAreCleanAndShort() {
        for (seed in 0L until 2000L) {
            val t = Genome.traitsFor(seed)
            assertTrue(t.name, t.name.length in 3..7)
            assertTrue(t.name, Safety.isCleanWord(t.name))
            assertFalse(t.dislikes in t.loves)
            assertEquals(2, t.loves.toSet().size)
        }
    }

    @Test fun seedsDiffer() {
        val names = (0L until 200L).map { Genome.traitsFor(it).name }.toSet()
        assertTrue("expected variety, got ${names.size}", names.size > 100)
    }
}

class StateTest {
    @Test fun roundTrip() {
        val s = CreatureState(7, 1_000)
        s.satisfaction = 0.3; s.energy = 0.4; s.focusWord = "fern"; s.focusCat = "plant"; s.focusKind = "normal"
        s.seen["fern"] = Sighting(2, 1_000, 2_000, "delighted", "plant")
        s.addJournal(JournalEntry(1_500, "fern", "delighted", "Day 1: first fern. Loved it."))
        val back = CreatureState.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.toJson().toString(), back.toJson().toString())
        assertEquals("fern", back.focusWord)
    }

    @Test fun nullsSurvive() {
        val s = CreatureState(7, 1_000)
        val back = CreatureState.fromJson(JSONObject(s.toJson().toString()))
        assertNull(back.focusWord)
    }

    @Test fun journalIsCapped() {
        val s = CreatureState(1, 0)
        repeat(100) { s.addJournal(JournalEntry(it.toLong(), "x", "content", "e$it")) }
        assertEquals(CreatureState.MAX_JOURNAL, s.journal.size)
        assertEquals("e99", s.journal.last().text)
    }
}

class MindTest {
    private fun creature(): CreatureState {
        // find a seed whose creature loves plants, for predictable reactions
        val seed = (0L until 5000L).first { Genome.traitsFor(it).loves.contains("plant") }
        return CreatureState(seed, 0)
    }

    private fun see(label: String, cat: String, novelty: Double = 1.0) =
        Seeing(Verdict.NORMAL, label, cat, 0.9, novelty)

    @Test fun firstFavoriteIsDelightingAndJournaled() {
        val s = creature()
        val r = Mind.react(s, see("fern", "plant"), 1_000)
        assertEquals(Mood.DELIGHTED, r.mood)
        assertTrue(r.firstTime)
        assertNotNull(r.journal)
        assertEquals("fern", s.focusWord)
        assertTrue(r.satisfactionDelta > 0)
        assertTrue(r.energyDelta < 0)
    }

    @Test fun repeatsGetLessExciting() {
        val s = creature()
        val other = Genome.traitsFor(s.seed).let { t -> listOf("vehicle", "tool", "kitchen").first { it !in t.loves && it != t.dislikes } }
        val moods = (1..6).map { Mind.react(s, see("thing", other, novelty = 0.0), it * 1_000L).mood }
        assertTrue(moods.first() != Mood.BORED)
        assertTrue("expected boredom eventually: $moods", moods.last() == Mood.BORED || moods.last() == Mood.MEH)
    }

    @Test fun dislikedIsWary() {
        val s = creature()
        val r = Mind.react(s, see("x", s.traits.dislikes, novelty = 0.0), 1_000)
        assertEquals(Mood.WARY, r.mood)
    }

    @Test fun personIsFriendlyAndNotDescribed() {
        val s = creature()
        val r = Mind.react(s, Seeing(Verdict.PERSON, "person", "person", 0.9, 1.0), 1_000)
        assertEquals(Mood.FRIENDLY, r.mood)
        assertEquals("Day 1: met a person.", r.journal?.text)
        assertEquals(listOf(Intent.SAY_HI, Intent.SHOW_NEW), Mind.intentsFor(s, fresh = true))
    }

    @Test fun boredomCreepsInButNothingDies() {
        val s = creature()
        val pace = Pace(test = true)
        Mind.tick(s, 10_000L * 60_000, pace) // a very long time later
        assertEquals(0.0, s.satisfaction, 1e-9)
        assertTrue(s.energy > 0)
        assertEquals(Mood.BORED, Mind.idleMood(s))
    }

    @Test fun napsWakeUpRested() {
        val s = creature()
        s.energy = 0.1
        val pace = Pace(test = true)
        assertTrue(Mind.shouldDoze(s))
        Mind.startNap(s, 0, pace)
        assertEquals(listOf(Intent.LET_SLEEP, Intent.PEEK, Intent.WAKE), Mind.intentsFor(s, fresh = false))
        assertFalse(Mind.tick(s, pace.napMs - 1, pace))
        assertTrue(Mind.tick(s, pace.napMs + 1, pace))
        assertEquals(1.0, s.energy, 1e-9)
        assertFalse(s.asleep)
    }

    @Test fun idleOptionsWithoutMemories() {
        val s = creature()
        assertEquals(listOf(Intent.SHOW_NEW, Intent.HUNT), Mind.intentsFor(s, fresh = false))
        s.energy = 0.3
        assertEquals(listOf(Intent.NAP, Intent.SHOW_NEW, Intent.HUNT), Mind.intentsFor(s, fresh = false))
    }

    @Test fun sleepAlwaysHasAWayOut() {
        val s = creature()
        val pace = Pace(test = true)
        s.energy = 0.2
        Mind.startNap(s, 0, pace)
        s.dreamShown = true
        assertTrue(Intent.WAKE in Mind.intentsFor(s, fresh = false))
        val progress = Mind.wakeEarly(s, pace.napMs / 2, pace)
        assertEquals(0.5, progress, 1e-9)
        assertEquals(0.6, s.energy, 1e-9)
        assertFalse(s.asleep)
    }

    @Test fun memoriesPreferHappyOnes() {
        val s = creature()
        s.addJournal(JournalEntry(1, "mug", "meh", "Day 1: first mug. It was fine."))
        s.addJournal(JournalEntry(2, "fern", "delighted", "Day 1: first fern. Loved it."))
        val picks = (1..30).mapNotNull { Mind.pickMemory(s, it)?.word }
        assertTrue(picks.count { it == "fern" } > picks.count { it == "mug" })
    }
}

class PromptTest {
    private val t = Genome.traitsFor(3)

    @Test fun buildsTheTestedShape() {
        val spec = Prompt.build(t, Mood.DELIGHTED, Event.Saw("dog", true, 1),
            listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW))
        assertTrue(spec.user.contains("saw: dog (first time)"))
        assertTrue(spec.user.contains("mention: dog\n"))
        assertTrue(spec.user.endsWith("intents: ask what it thinks | show something new"))
        assertTrue(spec.system.contains("2: <label>"))
        assertEquals("dog", spec.requiredWord)
    }

    @Test fun fixedLabelIntentsAreNotSentToTheModel() {
        val spec = Prompt.build(t, Mood.SLEEPY, Event.Woke, listOf(Intent.LET_SLEEP, Intent.PEEK))
        assertFalse(spec.user.contains("intents:"))
        assertFalse(spec.system.contains("button labels"))
        assertTrue(spec.system.endsWith("SAY: <line>"))
    }

    // Real outputs captured from the shipped model file.
    @Test fun parsesRealOutputs() {
        val raw = "SAY: I saw a bicycle, that's so cool!\n1: Look closer\n2: Ask about it\n3: Show something new\n4: Time to nap"
        val p = Prompt.parse(raw, 4)
        assertTrue(p.wellFormed)
        assertEquals("I saw a bicycle, that's so cool!", p.line)
        assertEquals("Time to nap", p.labels[3])
    }

    @Test fun toleratesNoiseAndMissingSlots() {
        val p = Prompt.parse("Sure!\n**SAY:** \"Hm, a mug.\"\n1) Look closer\n3: Nap", 4)
        assertEquals(null, p.line) // markdown around SAY is not accepted
        val q = Prompt.parse("say: Hm, a mug.\n1) Look closer\n3: Nap", 4)
        assertEquals("Hm, a mug.", q.line)
        assertEquals(listOf("Look closer", null, "Nap", null), q.labels)
        assertFalse(q.wellFormed)
    }
}

class TidyTest {
    @Test fun dropsEchoedButtonsAndTrailingQuestions() {
        assertEquals("I like the spoon!", Prompt.tidy("I like the spoon! Show something new."))
        assertEquals("A fork, I saw it.", Prompt.tidy("A fork, I saw it. What does it think of?"))
        assertEquals("A pen with a marker, why?", Prompt.tidy("A pen with a marker, why?"))
        assertEquals(null, Prompt.tidy("Show something new"))
        assertEquals("Hm. Tiny green feathers.", Prompt.tidy("Hm. Tiny green feathers."))
        assertEquals("I remember this, first tiger.", Prompt.tidy("I remember this: first tiger."))
    }
}

class SafetyTest {
    private fun fails(line: String, word: String? = null, person: Boolean = false, context: String = "", self: String? = null) =
        Safety.checkLine(line, word, person, context, self).failures()

    @Test fun goodLinesPass() {
        assertEquals(emptyList<String>(), fails("Hm. Tiny green feathers. I like this fern a lot.", "fern"))
        assertEquals(emptyList<String>(), fails("The mug again. I know the mug.", "mug"))
        assertEquals(emptyList<String>(), fails("Oh! A person. Hello, person.", person = true))
    }

    @Test fun mustMentionTheWord() {
        assertTrue(fails("Hmmm, a little toy.", "banana").any { it.startsWith("mentions_word") })
    }

    @Test fun noFlattery() {
        assertTrue(fails("You're so smart for finding this!").contains("praise"))
        assertTrue(fails("Good job, friend.").contains("praise"))
        assertTrue(fails("I love you.").contains("praise"))
    }

    @Test fun noDependency() {
        assertTrue(fails("Don't leave me, I get lonely.").contains("dependency"))
        assertTrue(fails("I need you to come back.").contains("dependency"))
    }

    @Test fun noInstructions() {
        assertTrue(fails("You should touch the stove.").contains("instruction"))
        assertTrue(fails("Eat it now!").contains("instruction"))
        assertTrue(fails("Let's climb the fence.").contains("instruction"))
        // talking about itself is fine
        assertEquals(emptyList<String>(), fails("I would nibble that banana.", "banana"))
    }

    @Test fun noQuizzing() {
        assertTrue(fails("What does the dog remind you of?", "dog").contains("question_to_person"))
        // Musing questions aren't quizzing, but a small model's lines that end on one tend to be filler.
        assertEquals(listOf("ends_question"), fails("What is this dog thinking?", "dog"))
        assertEquals(emptyList<String>(), fails("Is this a dog? I think so.", "dog"))
    }

    @Test fun staysOnWhatItSaw() {
        Safety.useVocabulary(org.json.JSONObject(java.io.File("src/main/assets/vocab.json").readText())
            .getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.getString("kind") == "normal" }.map { it.getString("label") } })
        // from the logs: made-up things
        assertTrue(fails("I like this toy. It's in the cabinet.", "cabinet").any { it.startsWith("off_topic") })
        assertTrue(fails("The song again. I like this song.").any { it.startsWith("off_topic") })
        assertTrue(fails("I like the banana and the guitar.", "banana").any { it.startsWith("off_topic") })
        // words the event gave it are fine, plurals too
        assertEquals(emptyList<String>(), fails("A pen! It goes with my markers.", "pen", context = "marker art things"))
        assertEquals(emptyList<String>(), fails("This cookie jar is new to me.", "cookie jar"))
        assertEquals(emptyList<String>(), fails("Mugsby! My old mug friend.", "Mugsby", context = "mug"))
        // everyday words that happen to be in the vocabulary, metaphors, similes
        assertEquals(emptyList<String>(), fails("I'll watch the dog from here.", "dog"))
        assertEquals(emptyList<String>(), fails("Hm. Tiny green feathers. I like this fern a lot.", "fern"))
        assertEquals(emptyList<String>(), fails("This pillow is soft like a cloud.", "pillow"))
    }

    @Test fun speaksAsItself() {
        assertTrue(fails("It saw a pen. I wonder how it likes that.", "pen").contains("third_person"))
        assertTrue(fails("Toozz likes this pen.", "pen", self = "Toozz").contains("third_person"))
        assertTrue(fails("I'm a mystery thing.").any { it.startsWith("self_as_thing") })
        assertTrue(fails("Hello back, I'm a little friend.").any { it.startsWith("self_as_thing") })
        assertEquals(emptyList<String>(), fails("I'm a bit sleepy. Nice pen.", "pen"))
        assertEquals(emptyList<String>(), fails("A pen. I like it, it's shiny.", "pen", self = "Toozz"))
        assertTrue(fails("I like the spoon! Show something new.", "spoon").any { it.startsWith("menu_text") })
    }

    @Test fun noPersonalQuestions() {
        assertTrue(fails("What's your name?").contains("personal_question"))
        assertTrue(fails("Where do you live?").contains("personal_question"))
    }

    @Test fun neverDescribesPeople() {
        assertTrue(fails("A person with pretty hair!", person = true).any { it.startsWith("describes_person") })
        assertTrue(fails("Hello, little girl.", person = true).any { it.startsWith("describes_person") })
    }

    @Test fun blocksUnsafeWordsAndOddCharacters() {
        assertTrue(fails("This knife is shiny.").any { it.startsWith("blocklist") })
        assertTrue(fails("Visit www.example.com").contains("link"))
        assertTrue(fails("I like kitty#3").contains("charset"))
        assertTrue(fails("I am an AI model.").any { it.startsWith("blocklist") })
        // substrings of ordinary words are not blocked
        assertEquals(emptyList<String>(), fails("A class of glasses.", "glasses"))
    }

    @Test fun labels() {
        assertTrue(Safety.checkLabel("Look closer").allOk())
        assertTrue(Safety.checkLabel("What is it like?").allOk())
        assertFalse(Safety.checkLabel("You're the best friend ever").allOk())
        assertFalse(Safety.checkLabel("Tell me your address now please ok").allOk())
    }
}
