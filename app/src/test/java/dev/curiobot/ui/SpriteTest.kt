package dev.curiobot.ui

import dev.curiobot.brain.Genome
import dev.curiobot.brain.Mood
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpriteTest {
    private fun grid(seed: Long, mood: Mood, asleep: Boolean = false): List<String> {
        val s = CreatureSprite(seed)
        val g = Array(16) { y -> CharArray(16) { x -> if (s.body[y][x]) '#' else '.' } }
        for ((y, x) in s.face(mood, asleep, blink = false)) if (y in 0..15 && x in 0..15) g[y][x] = '.'
        return g.map { String(it) }
    }

    @Test fun spritesAreSymmetricAndHaveFaces() {
        for (seed in 0L until 300L) {
            val s = CreatureSprite(seed)
            for (y in 0 until 16) for (x in 0 until 8) {
                assertTrue("seed $seed row $y not mirrored", s.body[y][x] == s.body[y][15 - x])
            }
            for (m in listOf(Mood.CONTENT, Mood.DELIGHTED, Mood.CURIOUS, Mood.WARY, Mood.MEH)) {
                val face = grid(seed, m)
                for (y in 0 until 16) assertTrue("seed $seed $m row $y not mirrored: ${face[y]}", face[y] == face[y].reversed())
            }
            // eyes must land on the body so they show as cut-outs
            assertTrue(s.body[s.eyeRow][4] && s.body[s.eyeRow][11])
        }
    }

    /** Writes a sheet of creatures for eyeballing: build/sprites.txt */
    @Test fun exportSheet() {
        val out = StringBuilder()
        for (seed in listOf(11L, 222L, 3333L, 7L, 42L, 99L, 1234L, 5L)) {
            out.appendLine("seed $seed ${Genome.traitsFor(seed).name}")
            for (m in listOf(Mood.CONTENT, Mood.DELIGHTED, Mood.WARY)) out.appendLine("mood ${m.word}").append(grid(seed, m).joinToString("\n")).appendLine()
            out.appendLine("asleep").append(grid(seed, Mood.SLEEPY, asleep = true).joinToString("\n")).appendLine()
        }
        File("build").mkdirs()
        File("build/sprites.txt").writeText(out.toString())
    }
}

class HatTest {
    @Test fun everyHatIsDrawnAndFitsEveryCreature() {
        for (h in dev.curiobot.brain.Hats.ALL) {
            val hat = Pix.HATS[h.id]
            assertTrue("no pixels for ${h.id}", hat != null)
            assertTrue(hat!!.rows.all { it.length == 16 })
            for (seed in 0L until 300L) {
                val s = CreatureSprite(seed)
                val top = s.headTop - hat.anchor
                // Fits under the status bar: at most one row above the sprite box.
                assertTrue("${h.id} seed $seed starts at row $top", top >= -1)
                // Never covers the face, in any mood.
                for (m in Mood.values()) for ((y, x) in s.face(m, asleep = false, blink = false) + s.face(m, true, false)) {
                    val c = hat.rows.getOrNull(y - top)?.getOrNull(x) ?: '.'
                    if ((y to x) !in s.spots) assertTrue("${h.id} covers the face at $y,$x for seed $seed", c == '.')
                }
            }
        }
    }
}
