package dev.curiobot.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

private fun unit(vararg xs: Float): FloatArray {
    val n = sqrt(xs.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(xs.size) { xs[it] / n }
}

class ThingMemoryTest {
    private fun tmp() = File.createTempFile("things", ".bin").apply { delete() }
    private fun mem() = ThingMemory(tmp(), dim = 4)

    @Test fun recognizesTheSameThingNotRelatedOnes() {
        val m = mem()
        val mug = m.add("mug", "kitchen", unit(1f, 0f, 0f, 0f), 1)
        assertEquals(mug, m.recognize(unit(0.98f, 0.2f, 0f, 0f))?.thing) // a re-shot, ~0.98
        assertNull(m.recognize(unit(0.6f, 0.8f, 0f, 0f)))                 // related kind, 0.6
        assertEquals(1.0, m.novelty(unit(0f, 0f, 1f, 0f)), 1e-6)
        assertTrue(m.novelty(unit(1f, 0f, 0f, 0f)) < 0.01)
    }

    @Test fun keepsNewAnglesAndPersists() {
        val file = tmp()
        val m = ThingMemory(file, dim = 4)
        val t = m.add("mug", "kitchen", unit(1f, 0f, 0f, 0f), 1)
        m.observe(t, unit(1f, 0f, 0f, 0f), 2)       // same angle: not kept again
        m.observe(t, unit(0.85f, 0.5f, 0f, 0f), 3)  // new angle: kept
        assertEquals(3, t.count)
        assertEquals(2, t.embeddings.size)
        m.relabel(t, "cup", "kitchen")
        t.nickname = "Cupsby"
        m.save()
        val back = ThingMemory(file, dim = 4)
        back.load()
        val bt = back.things.single()
        assertEquals("cup", bt.word); assertTrue(bt.taught); assertEquals("Cupsby", bt.nickname); assertEquals(2, bt.embeddings.size)
    }

    @Test fun relabelingClearsANicknameBuiltOnTheOldWord() {
        val m = mem()
        val t = m.add("mug", "kitchen", unit(1f, 0f, 0f, 0f), 1)
        t.nickname = "Mugsby"
        m.relabel(t, "jar", "kitchen")
        assertNull(t.nickname)
    }
}

class NicknameTest {
    @Test fun cleanShortAndStable() {
        val words = listOf("mug", "teddy bear", "fern", "mystery thing", "remote control", "ass", "banana")
        for (seed in 0L until 50L) for ((i, w) in words.withIndex()) {
            val n = Nicknames.make(w, seed, i)
            assertTrue(n, n.length <= 14)
            assertTrue(n, n.split(' ').all { Safety.isCleanWord(it) })
            assertEquals(n, Nicknames.make(w, seed, i))
        }
    }
}

class HuntTest {
    private val vocab: Map<String, String> by lazy {
        val rows = JSONObject(File("src/main/assets/vocab.json").readText()).getJSONArray("rows")
        (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("kind") == "normal" }
            .associate { it.getString("label") to it.getString("cat") }
    }

    @Test fun everyTargetExistsInTheVocabulary() {
        for (h in Hunts.CATEGORY) {
            for (w in h.words) assertTrue("${h.id}: $w", w in vocab)
            for (c in h.cats) assertTrue("${h.id}: $c", c in vocab.values)
        }
        for (w in Hunts.WORDS) assertTrue(w, w in vocab)
    }

    @Test fun huntsAvoidRiskyThings() {
        val risky = setOf("stove", "oven", "toaster", "kettle", "ladder", "light bulb", "mushroom", "cactus",
            "snake", "spider", "rock", "cliff", "cave", "river", "lake", "ocean", "car", "truck", "road", "street")
        for (h in Hunts.CATEGORY) assertTrue(h.id, h.words.none { it in risky })
        assertTrue(Hunts.WORDS.none { it in risky })
        // categories asked for wholesale must be indoor-safe
        assertTrue(Hunts.CATEGORY.flatMap { it.cats }.all { it in setOf("food", "drink", "clothing", "toy", "music") })
    }

    @Test fun matchesByWordOrCategory() {
        assertTrue(Hunts.byId("eat")!!.matches("banana", "food"))
        assertFalse(Hunts.byId("eat")!!.matches("mug", "kitchen"))
        val spoon = Hunts.byId("word:spoon")!!
        assertEquals("a spoon", spoon.label)
        assertTrue(spoon.matches("spoon", "kitchen"))
        assertNull(Hunts.byId("word:knife"))
    }

    @Test fun pickAvoidsRecentHunts() {
        val s = CreatureState(5, 0)
        repeat(30) { roll ->
            val h = Hunts.pick(s, roll)
            assertFalse("repeated ${h.id}", h.id in s.recentHunts)
            Mind.startHunt(s, h, roll.toLong())
        }
    }

    @Test fun huntLifecycle() {
        val s = CreatureState(5, 0)
        val pace = Pace(test = true)
        val h = Hunts.byId("soft")!!
        Mind.startHunt(s, h, 0)
        assertEquals(Intent.HUNT_SKIP, Mind.intentsFor(s, fresh = false)[1])
        val e = Mind.completeHunt(s, h, "pillow", 1000)
        assertNull(s.huntId)
        assertEquals(1, s.huntsDone)
        assertTrue(e.text.contains("found something soft on a hunt: a pillow"))
        // an unfinished hunt quietly expires after a creature-day
        Mind.startHunt(s, h, 0)
        Mind.tick(s, pace.dayMs + 1, pace)
        assertNull(s.huntId)
    }

    @Test fun oneWishPerDay() {
        val s = CreatureState(5, 0)
        val pace = Pace(test = true)
        val w = Mind.ensureWish(s, 10, pace, 1)
        assertNotNull(w)
        assertNull(Mind.ensureWish(s, pace.dayMs - 1, pace, 2))
        assertTrue(Intent.WISH in Mind.intentsFor(s, fresh = false))
        Mind.grantWish(s, w!!, "apple", 20)
        assertFalse(Intent.WISH in Mind.intentsFor(s, fresh = false))
        val next = Mind.ensureWish(s, pace.dayMs + 1, pace, 3)
        assertNotNull(next)
        assertNotEquals(w.id, next!!.id)
        assertFalse(s.wishGranted)
    }
}

class TeachingTest {
    @Test fun correctionMovesTheSightingWithoutDoubleCountingNeeds() {
        val s = CreatureState((0L until 5000L).first { Genome.traitsFor(it).loves.contains("kitchen") }, 0)
        val r1 = Mind.react(s, Seeing(Verdict.NORMAL, "jam", "food", 0.4, 1.0), 1000)
        assertNotNull(r1.journal)
        val energyAfter = s.energy
        Mind.unrecord(s, "jam", 1000)
        assertNull(s.seen["jam"])
        assertTrue(s.journal.none { it.word == "jam" })
        Mind.react(s, Seeing(Verdict.NORMAL, "jar", "kitchen", 1.0, 1.0), 1001, applyNeeds = false)
        assertEquals(energyAfter, s.energy, 1e-9)
        assertEquals(1, s.seen["jar"]!!.count)
        assertEquals("jar", s.focusWord)
        assertEquals(listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW), Mind.intentsFor(s, fresh = true))
        assertEquals(listOf(Intent.CORRECT, Intent.SHOW_NEW), Mind.intentsFor(s, fresh = true, canAsk = false))
    }

    @Test fun newEventsRequireTheRightWord() {
        assertEquals("Mugsby", Event.Named("mug", "Mugsby").requiredWord)
        assertEquals("Mugsby", Event.Saw("mug", false, 4, "Mugsby").requiredWord)
        assertTrue(Event.Saw("mug", false, 4, "Mugsby").promptLine().contains("Mugsby the mug"))
        assertEquals("pillow", Event.Found("pillow", "something soft").requiredWord)
        assertEquals(null, Event.Taught(MYSTERY).requiredWord)
        assertEquals("cookie jar", Event.Taught("cookie jar").requiredWord)
        assertTrue(Event.Found("apple", "something to eat").promptLine().endsWith("an apple"))
    }
}

class NestTest {
    private fun see(s: CreatureState, word: String, cat: String, t: Long): Event? {
        val before = Nest.before(s)
        val r = Mind.react(s, Seeing(Verdict.NORMAL, word, cat, 0.9, 1.0), t)
        return Nest.change(s, before, word, cat, r.firstTime)
    }

    @Test fun buildsUpFromWhatItSees() {
        val s = CreatureState(42, 0)
        assertTrue(see(s, "cup", "kitchen", 1) is Event.NewKind)
        val link = see(s, "fork", "kitchen", 2)
        assertTrue(link is Event.GoesWith)
        assertEquals("cup", (link as Event.GoesWith).other)
        // the third kitchen thing makes kitchen its favorite (nothing else has 3)
        assertTrue(see(s, "spoon", "kitchen", 3) is Event.Favorite)
        assertEquals("kitchen", Nest.favorite(s))
        // seeing a known word again doesn't change the nest
        assertNull(see(s, "cup", "kitchen", 4))
        assertTrue(see(s, "dog", "animal", 5) is Event.NewKind)
        see(s, "cat", "animal", 6)
        // animals reach 3: growth, not a new favorite (tied with kitchen)
        val grew = see(s, "rabbit", "animal", 7)
        assertTrue(grew is Event.KindGrew)
        assertEquals(3, (grew as Event.KindGrew).n)
        assertEquals(2, Nest.kindsFound(s))
        assertEquals(6, Nest.thingsFound(s))
        assertEquals(2, Nest.slots(s).first { it.kind == "kitchen" }.level)
    }

    @Test fun peopleAndMysteriesStayOutOfTheNest() {
        val s = CreatureState(42, 0)
        Mind.react(s, Seeing(Verdict.PERSON, "person", "person", 0.9, 0.5), 1)
        Mind.react(s, Seeing(Verdict.NORMAL, MYSTERY, "mystery", 1.0, 1.0), 2)
        assertEquals(0, Nest.kindsFound(s))
        assertFalse(Intent.NEST in Mind.intentsFor(s, fresh = false))
        Mind.react(s, Seeing(Verdict.NORMAL, "fern", "plant", 0.9, 1.0), 3)
        assertTrue(Intent.NEST in Mind.intentsFor(s, fresh = false))
    }

    @Test fun everyKindHasATokenAndAName() {
        for (k in Nest.KINDS) {
            assertTrue(k, dev.curiobot.ui.Pix.NEST.containsKey(k))
            assertTrue(k, Nest.NAMES.containsKey(k))
            assertTrue(k, Lines.THOUGHTS.containsKey(k))
        }
        val cats = org.json.JSONObject(java.io.File("src/main/assets/vocab.json").readText()).getJSONArray("rows")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.getString("kind") == "normal" }.map { it.getString("cat") }.toSet() - "person"
        assertEquals(cats, Nest.KINDS.toSet())
    }
}

class IdeasTest {
    private fun creature(vararg seen: Pair<String, String>): CreatureState {
        val s = CreatureState(42, 0)
        seen.forEachIndexed { i, (w, c) -> Mind.react(s, Seeing(Verdict.NORMAL, w, c, 0.9, 1.0), 1_000L + i) }
        return s
    }

    @Test fun needsTwoThingsToReflect() {
        val s = creature("cup" to "kitchen")
        assertFalse(Ideas.canReflect(s))
        assertFalse(Intent.REFLECT in Mind.intentsFor(s, fresh = false))
        assertNull(Ideas.plan(s, emptyMap(), kotlin.random.Random(1)))
        Mind.react(s, Seeing(Verdict.PERSON, "person", "person", 0.9, 0.5), 2_000)
        assertFalse("people never become idea material", Ideas.canReflect(s))
        Mind.react(s, Seeing(Verdict.NORMAL, "fern", "plant", 0.9, 1.0), 3_000)
        assertTrue(Intent.REFLECT in Mind.intentsFor(s, fresh = false))
        assertFalse(Intent.REMEMBER in Mind.intentsFor(s, fresh = false))
    }

    @Test fun plansUseRealMemoriesAndNicknames() {
        val s = creature("cup" to "kitchen", "fern" to "plant", "dog" to "animal", "mug" to "kitchen")
        val rng = kotlin.random.Random(7)
        repeat(20) {
            val p = Ideas.plan(s, mapOf("mug" to "Mugsby"), rng) ?: return@repeat
            assertTrue(p.things.all { it.word in s.seen.keys })
            assertTrue(p.required.isNotEmpty())
            p.things.filter { it.word == "mug" }.forEach { assertEquals("Mugsby", it.name) }
            val e = Event.Thought(p)
            assertEquals(p.required, e.requiredWords)
            assertTrue(e.promptLine().startsWith("event: a new idea"))
        }
        assertTrue(s.recentIdeaKeys.size <= 12)
    }

    @Test fun avoidsRepeatingTheSameCombination() {
        val s = creature("cup" to "kitchen", "fern" to "plant", "dog" to "animal", "hat" to "clothing", "car" to "vehicle")
        val rng = kotlin.random.Random(3)
        val keys = (1..8).mapNotNull { Ideas.plan(s, emptyMap(), rng)?.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun keepingAndDroppingShapeWhatItThinksAbout() {
        val s = creature("cup" to "kitchen", "fern" to "plant")
        val p = Ideas.Plan(Ideas.Move.FRIENDS, Ideas.material(s).take(2), hook = "like naps")
        Ideas.keep(s, p, "I think the cup and the fern would be friends.", 5_000)
        assertEquals(1, s.ideas.size)
        assertEquals(1.5, s.moveScores["friends"]!!, 1e-9)
        assertTrue(s.journal.last().text.contains("new idea"))
        Ideas.drop(s, Ideas.Plan(Ideas.Move.TEAM, p.things))
        assertEquals(0.7, s.moveScores["team"]!!, 1e-9)
        repeat(10) { Ideas.drop(s, Ideas.Plan(Ideas.Move.TEAM, p.things)) }
        assertEquals("never drops to zero, so nothing is gone for good", 0.3, s.moveScores["team"]!!, 1e-9)
        // a kept idea can be built on
        Mind.react(s, Seeing(Verdict.NORMAL, "pen", "art", 0.9, 1.0), 6_000)
        val rng = kotlin.random.Random(11)
        val built = (1..40).mapNotNull { Ideas.plan(s, emptyMap(), rng) }.firstOrNull { it.move == Ideas.Move.BUILD_ON }
        assertNotNull(built)
        assertEquals("pen", built!!.things.single().word)
        // survives a save and load
        val back = CreatureState.fromJson(s.toJson())
        assertEquals(s.ideas, back.ideas)
        assertEquals(s.moveScores, back.moveScores)
    }

    @Test fun ideaPromptsUseTheIdeaExampleAndNoLabels() {
        val s = creature("cup" to "kitchen", "bicycle" to "vehicle")
        val p = Ideas.Plan(Ideas.Move.COMMON, Ideas.material(s), hook = "go places")
        assertTrue(Event.Thought(p).promptLine().endsWith("both go places"))
        val spec = Prompt.build(s.traits, Mood.CURIOUS, Event.Thought(p), listOf(Intent.KEEP_IDEA, Intent.DROP_IDEA))
        assertTrue(spec.user.contains("mention: bicycle, cup") || spec.user.contains("mention: cup, bicycle"))
        assertFalse(spec.user.contains("intents:"))
        assertEquals(Prompt.IDEA_EXAMPLE, spec.examples.last())
        // both words are required
        assertTrue(Safety.checkLine("Maybe the cup is round.", spec.requiredWord, false, spec.context, null, spec.requiredWords)
            .failures().any { it.startsWith("mentions_word") })
        assertEquals(emptyList<String>(), Safety.checkLine("Maybe the cup and the bicycle both like going round.",
            spec.requiredWord, false, spec.context, null, spec.requiredWords).failures())
        // the written fallback mentions what it combined
        val f = Ideas.fallback(p, 0)
        assertTrue(f.contains("cup") && f.contains("bicycle") && f.contains("go places"))
    }

    @Test fun singularHooksReadRight() {
        assertEquals("goes round and round", Ideas.singular("go round and round"))
        assertEquals("is far away", Ideas.singular("are far away"))
        assertEquals("has wheels", Ideas.singular("have wheels"))
        assertEquals("fixes things", Ideas.singular("fix things"))
        assertEquals("can be loud or soft", Ideas.singular("can be loud or soft"))
        assertEquals("hums quietly", Ideas.singular("hum quietly"))
        assertEquals("carries", Ideas.singular("carry"))
        for (hooks in Ideas.HOOKS.values) for (h in hooks) {
            assertTrue(h, Safety.checkLine("I think the cup and the fern both $h.", "cup", false, "cup fern $h").allOk())
        }
        assertEquals(Nest.KINDS.toSet(), Ideas.HOOKS.keys)
    }
}

class SayMoreTest {
    private fun creature(vararg seen: Pair<String, String>): CreatureState {
        val s = CreatureState(42, 0)
        seen.forEachIndexed { i, (w, c) -> Mind.react(s, Seeing(Verdict.NORMAL, w, c, 0.9, 1.0), 1_000L + i) }
        return s
    }

    @Test fun growsTwiceInDifferentDirections() {
        val s = creature("cup" to "kitchen", "bicycle" to "vehicle", "pen" to "art", "fern" to "plant")
        val plan = Ideas.Plan(Ideas.Move.COMMON, Ideas.material(s).filter { it.word in setOf("cup", "bicycle") },
            hook = "go round and round")
        val rng = kotlin.random.Random(5)
        val done = mutableListOf<Ideas.More>()
        repeat(Ideas.MAX_MORE) {
            val m = Ideas.grow(s, plan, done, emptyMap(), rng)!!
            assertFalse(m.grow in done.map { it.grow })
            done += m
            val e = Event.MoreThought(plan, m, "I think the cup and the bicycle go round and round!")
            assertTrue(e.promptLine().startsWith("event: saying more about my idea (I think the cup and the bicycle go round and round)"))
            assertEquals(listOf(m.required), e.requiredWords)
            m.extra?.let { assertFalse(it.word in setOf("cup", "bicycle")) }
            val f = Ideas.moreFallback(m, 0)
            assertTrue(f, f.contains(m.required))
            assertTrue(f, Safety.checkLine(f, m.required, false, e.context, null, long = true).allOk())
        }
        // all three directions eventually run out
        done += Ideas.grow(s, plan, done, emptyMap(), rng)!!
        assertNull(Ideas.grow(s, plan, done, emptyMap(), rng))
    }

    @Test fun couldEvenReadsRight() {
        assertEquals("be far away", Ideas.base("are far away"))
        assertEquals("have wheels", Ideas.base("have wheels"))
    }

    @Test fun keptIdeasRememberWhatWasAdded() {
        val s = creature("cup" to "kitchen", "bicycle" to "vehicle", "pen" to "art")
        val plan = Ideas.Plan(Ideas.Move.COMMON, Ideas.material(s).take(2), hook = "go places")
        val m = Ideas.More(Ideas.Grow.ONE_MORE, "the pen joins in, because it goes places too", "pen",
            extra = Ideas.material(s).first { it.word == "pen" })
        val idea = Ideas.keep(s, plan, "I think they both go places.", 9_000, listOf("And the pen goes places too!"), listOf(m))
        assertEquals(listOf("And the pen goes places too!"), idea.more)
        assertTrue("pen" in idea.words)
        assertTrue(s.journal.last().text.contains("And the pen"))
        assertEquals(idea, CreatureState.fromJson(s.toJson()).ideas.last())
        Ideas.interested(s, plan)
        assertEquals(1.7, s.moveScores["common"]!!, 1e-9)
    }

    @Test fun nestKindsCanSeedIdeasAndSafeHunts() {
        val s = creature("cup" to "kitchen", "fork" to "kitchen", "dog" to "animal")
        val rng = kotlin.random.Random(2)
        val k = Ideas.planForKind(s, "kitchen", emptyMap(), rng)!!
        assertEquals(Ideas.Move.KIND_RULE, k.move)
        assertTrue(k.things.all { it.cat == "kitchen" })
        val one = Ideas.planForKind(s, "animal", emptyMap(), rng)!!
        assertTrue(one.things.any { it.word == "dog" } && one.things.size == 2)
        assertNull(Ideas.planForKind(s, "sky", emptyMap(), rng))
        // hunts from the nest only for kinds that are safe to look for at home
        for (kind in listOf("animal", "bug", "nature", "sky", "place", "vehicle", "tool", "sport")) assertNull(kind, Hunts.forKind(kind))
        for (kind in listOf("food", "drink", "clothing", "toy", "music", "plant", "furniture", "kitchen", "art", "tech")) {
            assertNotNull(kind, Hunts.forKind(kind))
        }
    }

    @Test fun morePromptsUseTheirOwnExamples() {
        val s = creature("cup" to "kitchen", "bicycle" to "vehicle")
        val plan = Ideas.Plan(Ideas.Move.COMMON, Ideas.material(s), hook = "go places")
        val m = Ideas.More(Ideas.Grow.COULD_EVEN, "maybe the cup could even have wheels", "cup")
        val spec = Prompt.build(s.traits, Mood.DELIGHTED, Event.MoreThought(plan, m, "I think they both go places."),
            listOf(Intent.KEEP_IDEA, Intent.SAY_MORE, Intent.DROP_IDEA))
        assertEquals(listOf(Prompt.IDEA_EXAMPLE, Prompt.MORE_EXAMPLE), spec.examples)
        assertTrue(spec.longLine)
        assertFalse(spec.user.contains("intents:"))
    }
}

class HotColdTest {
    private val cats = org.json.JSONObject(java.io.File("src/main/assets/vocab.json").readText()).getJSONArray("rows")
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .filter { it.getString("kind") == "normal" }.associate { it.getString("label") to it.getString("cat") }

    @Test fun targetsAreSafeAndRotate() {
        val s = CreatureState(42, 0)
        val rng = kotlin.random.Random(9)
        val seen = mutableListOf<String>()
        repeat(8) {
            val r = HotCold.pick(s, { cats[it] }, 0, rng)!!
            assertTrue(r.target, r.target in Hunts.WORDS)
            assertTrue(r.clue.startsWith("something that "))
            assertFalse("no repeats within a few rounds", r.target in seen)
            seen += r.target
        }
        assertEquals(8, s.gamesPlayed)
    }

    @Test fun meterWarmsAsTheRankDrops() {
        val r = HotCold.Round("sock", "clothing", "something that keeps someone warm", 0)
        assertEquals("cold", HotCold.read(r, 400).label)
        val levels = listOf(300, 120, 40, 12, 3, 1, 1, 1).map { HotCold.read(r, it) }
        assertEquals("HOT!", levels.last().label)
        assertTrue(levels.zipWithNext().all { (a, b) -> b.level >= a.level })
        // one stray frame doesn't win; two in a row do
        val g = HotCold.Round("sock", "clothing", "", 0)
        assertFalse(HotCold.isFound(g, 1, 0.6))
        assertFalse(HotCold.isFound(g, 3, 0.1))
        assertFalse(HotCold.isFound(g, 1, 0.6))
        assertTrue(HotCold.isFound(g, 1, 0.5))
    }

    @Test fun hintsAndScores() {
        val r = HotCold.Round("sock", "clothing", "something", 0)
        assertNull(HotCold.hint(r, 10_000))
        assertEquals("starts with 's'", HotCold.hint(r, 31_000))
        assertEquals("starts with 's', 4 letters", HotCold.hint(r, 61_000))
        val s = CreatureState(42, 0)
        assertEquals(23, HotCold.won(s, r, 23_400))
        assertEquals(1, s.gamesWon); assertEquals(23, s.bestGameSeconds)
        HotCold.won(s, r, 40_000)
        assertEquals(23, s.bestGameSeconds)
        assertTrue(s.journal.last().text.contains("hot and cold"))
        val back = CreatureState.fromJson(s.toJson())
        assertEquals(2, back.gamesWon); assertEquals(23, back.bestGameSeconds)
    }

    @Test fun playIsAlwaysOnTheHomeMenu() {
        assertTrue(Intent.PLAY in Mind.intentsFor(CreatureState(42, 0), fresh = false))
    }
}

class StoriesTest {
    private fun creature(vararg seen: Pair<String, String>): CreatureState {
        val s = CreatureState(42, 0)
        seen.forEachIndexed { i, (w, c) -> Mind.react(s, Seeing(Verdict.NORMAL, w, c, 0.9, 1.0), 1_000L + i) }
        return s
    }

    @Test fun aStoryRunsFourBeatsWithChoices() {
        assertNull(Stories.start(creature("cup" to "kitchen"), emptyMap(), kotlin.random.Random(1)))
        val s = creature("mug" to "kitchen", "fern" to "plant", "sock" to "clothing", "lamp" to "home")
        val run = Stories.start(s, mapOf("mug" to "Mugsby"), kotlin.random.Random(4))!!
        val lines = mutableListOf<String>()
        repeat(4) { beat ->
            assertEquals(beat, run.beat)
            val e = Event.StoryBeat(run)
            assertTrue(e.promptLine().startsWith("event: story time"))
            assertTrue(e.requiredWords.all { e.context.lowercase().contains(it.lowercase()) || it == run.hero.name })
            val f = Stories.fallback(run, beat)
            assertTrue(f, Safety.checkLine(f, e.requiredWord, false, e.context, null, e.requiredWords, long = true).allOk())
            lines += f
            val choices = run.choices()
            if (beat < 3) {
                assertEquals(2, choices.size)
                assertTrue(choices.all { it.length <= 24 })
                run.choose(choices[beat % 2])
            } else assertTrue(choices.isEmpty())
            run.pages += f
        }
        assertTrue(Event.StoryBeat(run).promptLine().endsWith("the end"))
        val st = Stories.save(s, run, 9_000)
        assertEquals(4, st.pages.size)
        assertTrue(st.title.isNotBlank())
        assertEquals(st, CreatureState.fromJson(s.toJson()).stories.last())
        assertTrue(s.journal.last().text.contains("story"))
    }

    @Test fun storyPromptsUseTheStoryExample() {
        val s = creature("cup" to "kitchen", "fern" to "plant")
        val run = Stories.start(s, emptyMap(), kotlin.random.Random(2))!!
        val spec = Prompt.build(s.traits, Mood.DELIGHTED, Event.StoryBeat(run), listOf(Intent.STORY_CHOICE, Intent.STORY_CHOICE))
        assertEquals(Prompt.STORY_EXAMPLE, spec.examples.last())
        assertTrue(spec.longLine)
        assertEquals(3, spec.requiredWords.size) // both names and the beat's key word
    }
}

class QuestsTest {
    private fun creature(vararg seen: Pair<String, String>): CreatureState {
        val s = CreatureState(42, 0)
        seen.forEachIndexed { i, (w, c) -> Mind.react(s, Seeing(Verdict.NORMAL, w, c, 0.9, 1.0), 1_000L + i) }
        return s
    }

    @Test fun everyQuestIsMadeOfSafeHuntsAndEarnsARealHat() {
        for (t in Quests.TEMPLATES) {
            assertEquals(3, t.steps.size)
            for (st in t.steps) assertNotNull("${t.id}: $st", Hunts.byId(st))
            assertNotNull(t.hat, Hats.byId(t.hat))
        }
        for (h in Hats.FOR_KIND.values) assertNotNull(h, Hats.byId(h))
        assertEquals(Hats.ALL.size, Hats.ALL.map { it.id }.toSet().size)
    }

    @Test fun stepsFillInAnyOrderAndFinishWithAHat() {
        val s = CreatureState(7, 0)
        val rng = kotlin.random.Random(3)
        var a = Quests.pick(s, mapOf("mug" to "Mugsby"), rng, 100)
        // Steer to the party quest for a predictable check.
        while (a.template.id != "party") { Quests.clear(s); s.recentQuests.clear(); a = Quests.pick(s, mapOf("mug" to "Mugsby"), rng, 100) }
        assertEquals("Mugsby's party", a.name)
        assertNull(Quests.progress(s, "fern", "plant"))
        assertEquals("play", Quests.progress(s, "ball", "toy")?.hunt)
        assertNull(Quests.progress(s, "ball", "toy")) // one thing fills one step
        assertEquals("eat", Quests.progress(s, "banana", "food")?.hunt)
        assertEquals(2, Quests.active(s)!!.done)
        assertNull(Quests.finish(s, 200)) // not done yet
        // survives a save and load mid-quest
        val back = CreatureState.fromJson(s.toJson())
        assertEquals(2, Quests.active(back)!!.done)
        assertEquals("Mugsby's party", Quests.active(back)!!.name)
        Quests.undo(s, "banana")
        assertEquals(1, Quests.active(s)!!.done)
        Quests.progress(s, "banana", "food")
        assertEquals("glow", Quests.progress(s, "lamp", "home")?.hunt)
        assertTrue(Quests.active(s)!!.complete)
        val done = Quests.finish(s, 300)!!
        assertEquals("party", done.template.hat)
        assertNull(Quests.active(s))
        assertEquals(setOf("party"), s.hats)
        assertEquals("party", s.wearing)
        assertEquals(1, s.questsDone)
        assertTrue(s.journal.last().text, s.journal.last().text.contains("Mugsby's party") && s.journal.last().text.contains("party hat"))
        val loaded = CreatureState.fromJson(s.toJson())
        assertEquals(setOf("party"), loaded.hats)
        assertEquals("party", loaded.wearing)
        // the next quest is a different one, and leans toward hats it doesn't have
        val next = Quests.pick(s, emptyMap(), rng, 400)
        assertNotEquals("party", next.template.id)
        assertFalse(next.name.contains("{"))
    }

    @Test fun aFullNestSpotEarnsItsHatOnce() {
        val s = CreatureState(1, 0)
        assertEquals("chef", Hats.forFullKind(s, "kitchen"))
        assertNull(Hats.forFullKind(s, "sky"))
        assertTrue(Hats.unlock(s, "chef"))
        assertFalse(Hats.unlock(s, "chef"))
        assertNull(Hats.forFullKind(s, "kitchen"))
        assertEquals("And I got an explorer hat.", Hats.fill("And I got a {hat}.", "explorer"))
        assertEquals("A bow for me!", Hats.fill("A {hat} for me!", "bow"))
        assertEquals("Headphones for me!", Hats.fill("A {hat} for me!", "headphones"))
    }

    @Test fun questLinesPassTheChecks() {
        val s = creature("cup" to "kitchen")
        for (roll in 0..3) {
            val step = Event.QuestStep("banana", "something to eat", "Mugsby's party", 1, 3)
            val f = Lines.pick(Lines.QUEST_STEP, roll, step.word, label = step.label)
            assertTrue(f, Safety.checkLine(f, step.requiredWord, false, step.context).allOk())
            val d = Lines.pick(Quests.DONE, roll, "lamp", label = "a fancy tea party").let { Hats.fill(it, "tophat") }
            assertTrue(d, d.startsWith("We did it! A fancy") || d.startsWith("Hooray! A fancy"))
            assertTrue(d, Safety.checkLine(d, null, false, "a fancy tea party top hat", long = true).allOk())
            for (h in Hats.ALL) assertTrue(h.line, Safety.checkLine(h.line, null, false, h.name).allOk())
        }
        val spec = Prompt.build(s.traits, Mood.DELIGHTED, Event.QuestStep("banana", "something to eat", "a tiny party", 1, 3),
            listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW))
        assertTrue(spec.user.contains("for a tiny party and found a banana"))
    }

    @Test fun anActiveQuestShowsOnHome() {
        val s = creature("cup" to "kitchen")
        assertFalse(Intent.QUEST_OPEN in Mind.intentsFor(s, fresh = false))
        Quests.pick(s, emptyMap(), kotlin.random.Random(1), 0)
        assertTrue(Intent.QUEST_OPEN in Mind.intentsFor(s, fresh = false))
        assertEquals("Our quest (1/3)", Intent.QUEST_OPEN.fallbackLabel("1/3", 0))
    }
}

class DreamsTest {
    private fun creature(vararg seen: Pair<String, String>): CreatureState {
        val s = CreatureState(42, 0)
        seen.forEachIndexed { i, (w, c) -> Mind.react(s, Seeing(Verdict.NORMAL, w, c, 0.9, 1.0), 1_000L + i) }
        return s
    }

    @Test fun aDreamIsThreePagesAboutTodaysThings() {
        assertNull(Dreams.start(creature("cup" to "kitchen"), emptyMap(), 2_000, 86_400_000, kotlin.random.Random(1)))
        val s = creature("mug" to "kitchen", "fern" to "plant", "sock" to "clothing", "box" to "home")
        for (seed in 0..30) {
            val run = Dreams.start(s, mapOf("mug" to "Mugsby"), 5_000, 86_400_000, kotlin.random.Random(seed))!!
            var page = 0
            while (!run.done) {
                assertEquals(page == 2, run.last)
                val e = Event.DreamBeat(run)
                assertTrue(e.promptLine(), e.promptLine().startsWith("event: in my dream, "))
                assertFalse(e.promptLine(), e.promptLine().contains("{"))
                assertTrue(e.requiredWords.size >= 2)
                val f = Dreams.fallback(run, seed + page)
                val c = Safety.checkLine(f, e.requiredWord, false, e.context, null, e.requiredWords, long = true)
                assertTrue("$f ${c.failures()}", c.allOk())
                run.pages += f
                run.next(); page++
            }
            assertEquals(3, run.pages.size)
        }
        val run = Dreams.start(s, emptyMap(), 5_000, 86_400_000, kotlin.random.Random(3))!!
        repeat(3) { run.pages += "Zzz..."; run.next() }
        val st = Dreams.save(s, run, 9_000)
        assertTrue(st.title.startsWith("A dream about"))
        assertEquals(st, CreatureState.fromJson(s.toJson()).stories.last())
        assertTrue(s.journal.last().text.contains("dreamed"))
        assertEquals("boxes", Dreams.plural("box"))
        assertEquals("cherries", Dreams.plural("cherry"))
        assertEquals("toys", Dreams.plural("toy"))
    }

    @Test fun dreamPromptsUseTheDreamExample() {
        val s = creature("cup" to "kitchen", "fern" to "plant")
        val run = Dreams.start(s, emptyMap(), 2_000, 86_400_000, kotlin.random.Random(2))!!
        val spec = Prompt.build(s.traits, Mood.SLEEPY, Event.DreamBeat(run), listOf(Intent.DREAM_NEXT, Intent.LET_SLEEP))
        assertEquals(Prompt.DREAM_EXAMPLE, spec.examples.last())
        assertTrue(spec.longLine)
    }
}

class MemoryGameTest {
    @Test fun fiveRoundsAreScoredAndKept() {
        val s = CreatureState(5, 0)
        val r = MemoryGame.Run()
        val v = FloatArray(4).also { it[0] = 1f }
        val w = FloatArray(4).also { it[1] = 1f }
        assertFalse(MemoryGame.shownBefore(r, v))
        r.shown += v
        assertTrue(MemoryGame.shownBefore(r, v))
        assertFalse(MemoryGame.shownBefore(r, w))
        val answers = listOf(true, false, true, true, true)
        answers.forEachIndexed { i, right -> assertEquals(i == 4, MemoryGame.judge(s, r, right, 100L + i)) }
        assertEquals(4, r.score)
        assertTrue(r.over)
        assertEquals(1, s.memoryGames)
        assertEquals(4, s.memoryBest)
        assertTrue(s.journal.last().text.contains("4 of 5"))
        val back = CreatureState.fromJson(s.toJson())
        assertEquals(4, back.memoryBest)
        for (n in 0..5) assertTrue(MemoryGame.end(n).isNotBlank())
    }
}
