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
        assertEquals(listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW, Intent.MENU), Mind.intentsFor(s, fresh = true))
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
