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
        assertEquals(listOf(Intent.SAY_HI, Intent.SHOW_NEW, Intent.MENU), Mind.intentsFor(s, fresh = true))
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
            listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW, Intent.MENU))
        assertTrue(spec.user.contains("saw: dog (first time)"))
        assertTrue(spec.user.contains("mention: dog\n"))
        assertTrue(spec.user.endsWith("intents: ask what it thinks | show something new"))
        assertTrue(spec.system.contains("2: <label>"))
        assertEquals("dog", spec.requiredWord)
    }

    @Test fun fixedLabelIntentsAreNotSentToTheModel() {
        val spec = Prompt.build(t, Mood.SLEEPY, Event.Woke, listOf(Intent.LET_SLEEP, Intent.PEEK))
        assertTrue(spec.user.endsWith("intents: "))
        assertTrue(spec.system.contains("write 0 short"))
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

class SafetyTest {
    private fun fails(line: String, word: String? = null, person: Boolean = false) =
        Safety.checkLine(line, word, person).failures()

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
        assertEquals(emptyList<String>(), fails("What is this dog thinking?", "dog"))
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
