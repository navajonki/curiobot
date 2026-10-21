package dev.curiobot.ui

import dev.curiobot.brain.Mood
import java.util.SplittableRandom
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A 16x16 creature drawn from its seed: body shape, appendage, eye size and
 * spots all come from the seed, so every creature looks like itself.
 */
class CreatureSprite(seed: Long) {
    val body = Array(16) { BooleanArray(16) }
    val eyeRow: Int
    val eyeSize: Int
    val mouthRow: Int
    private val leftEye = 4
    private val rightEye: Int
    val spots = mutableListOf<Pair<Int, Int>>()

    init {
        val r = SplittableRandom(seed xor 0x5EED5EEDL)
        val shape = r.nextInt(4)
        val jitter = DoubleArray(16) { (r.nextDouble() - 0.5) * 0.8 }
        var top = 16
        var bottom = 0
        for (y in 1..14) {
            val hw = when (shape) {
                0 -> ellipse(y, 8.5, 6.5, 6.5)          // round
                1 -> ellipse(y, 8.5, 5.0, 6.8)          // tall
                2 -> ellipse(y, 9.5, 7.3, 5.2)          // wide
                else -> 2.5 + (y - 2) * 0.42            // pear
            }.let { if (it > 0) it + (jitter[y] + jitter[y - 1]) / 2 else it }
            if (shape == 3 && (y < 3 || y > 13)) continue
            if (hw < 1) continue
            val half = ceil(hw).toInt().coerceAtMost(7)
            for (x in (8 - half) until 8) { body[y][x] = true; body[y][15 - x] = true }
            top = minOf(top, y); bottom = max(bottom, y)
        }
        // appendage
        when (r.nextInt(5)) {
            1 -> for (x in listOf(5, 10)) { // antennae
                for (dy in 1..2) if (top - dy >= 0) body[top - dy][x] = true
                if (top - 3 >= 0) { body[top - 3][x] = true; body[top - 3][if (x < 8) x - 1 else x + 1] = true }
            }
            2 -> for (x in listOf(4, 11)) { // ears
                if (top - 1 >= 0) { body[top - 1][x] = true; body[top - 1][if (x < 8) x + 1 else x - 1] = true }
                if (top - 2 >= 0) body[top - 2][x] = true
            }
            3 -> { // sprout
                for (dy in 1..2) if (top - dy >= 0) { body[top - dy][7] = true; body[top - dy][8] = true }
                if (top - 3 >= 0) { body[top - 3][6] = true; body[top - 3][9] = true }
            }
            4 -> for (x in listOf(5, 10)) if (top - 1 >= 0) body[top - 1][x] = true // nubs
        }
        // feet
        if (bottom + 1 <= 15) for (x in listOf(5, 6, 9, 10)) body[bottom + 1][x] = true
        val h = bottom - top + 1
        eyeRow = (top + h * 0.33).toInt().coerceIn(4, 9)
        eyeSize = 1 + r.nextInt(2)
        rightEye = 15 - leftEye - (eyeSize - 1)
        mouthRow = (eyeRow + eyeSize + 2).coerceAtMost(bottom - 1)
        // make sure the face area is solid
        for (y in eyeRow..mouthRow + 1) for (x in 3..12) if (y in top..bottom) body[y][x] = true
        // a few light spots on the lower body
        repeat(r.nextInt(4)) {
            val sy = (mouthRow + 2 + r.nextInt(3)).coerceAtMost(bottom - 1)
            val sx = 3 + r.nextInt(4)
            if (body[sy][sx]) spots += sy to sx
            if (body[sy][15 - sx]) spots += sy to (15 - sx)
        }
    }

    /** Light pixels (cut-outs) for eyes and mouth, given mood and state. */
    fun face(mood: Mood, asleep: Boolean, blink: Boolean): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        out += spots
        // Spans are chosen so the two eyes mirror each other exactly.
        for ((ex, isLeft) in listOf(leftEye to true, rightEye to false)) {
            val s = eyeSize
            when {
                asleep || blink -> for (x in (ex - 1)..(ex + s)) out += (eyeRow + s - 1) to x
                mood == Mood.DELIGHTED -> {
                    out += (eyeRow + 1) to (ex - 1); out += (eyeRow + 1) to (ex + s)
                    for (x in ex until ex + s) out += eyeRow to x
                }
                mood == Mood.WARY -> out += eyeRow to (if (isLeft) ex else ex + s - 1)
                else -> for (dy in 0 until s) for (dx in 0 until s) out += (eyeRow + dy) to (ex + dx)
            }
        }
        val m = mouthRow
        when {
            asleep -> { out += m to 7; out += m to 8 }
            mood in setOf(Mood.DELIGHTED, Mood.FRIENDLY, Mood.CONTENT) -> {
                out += m to 6; out += (m + 1) to 7; out += (m + 1) to 8; out += m to 9
            }
            mood in setOf(Mood.CURIOUS, Mood.PUZZLED) -> { out += m to 7; out += m to 8; out += (m + 1) to 7; out += (m + 1) to 8 }
            mood == Mood.WARY -> { out += (m + 1) to 6; out += m to 7; out += m to 8; out += (m + 1) to 9 } // small frown
            else -> for (x in 6..9) out += m to x
        }
        return out
    }

    private fun ellipse(y: Int, cy: Double, rx: Double, ry: Double): Double {
        val d = (y - cy) / ry
        return if (d * d >= 1) 0.0 else rx * sqrt(1 - d * d)
    }
}

/** Tiny fixed pictures, '#' = dark pixel. */
object Pix {
    val EGG = listOf(
        "......####......",
        "....##....##....",
        "...#........#...",
        "..#...##.....#..",
        "..#..####....#..",
        ".#....##......#.",
        ".#............#.",
        ".#.......##...#.",
        "#.......####...#",
        "#........##....#",
        "#..##..........#",
        "#.####.........#",
        ".#.##.........#.",
        ".#............#.",
        "..##........##..",
        "....########....",
    )
    val CRACK = listOf(5 to 3, 6 to 4, 7 to 3, 8 to 4, 9 to 5, 10 to 4, 6 to 5, 9 to 6)
    val EYE = listOf("..###..", ".#...#.", "#..#..#", ".#...#.", "..###..")
    val BOLT = listOf("...#.", "..#..", ".###.", "..#..", ".#...")
    val STAR = listOf("....#....", "....#....", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", ".#.....#.")
    /** One 12x12 token per kind of thing, for the nest screen. */
    val NEST = mapOf(
        "animal" to listOf(
            "............",
            "...##..##...",
            "...##..##...",
            ".##......##.",
            ".##......##.",
            "....####....",
            "...######...",
            "..########..",
            "..########..",
            "...######...",
            "............",
            "............",
        ),
        "bug" to listOf(
            "...#....#...",
            "....#..#....",
            "....####....",
            "..########..",
            ".###.##.###.",
            ".##..##..##.",
            ".###.##.###.",
            ".##..##..##.",
            ".###.##.###.",
            "..##.##.##..",
            "...######...",
            "............",
        ),
        "plant" to listOf(
            "............",
            ".......####.",
            "......#####.",
            ".##...####..",
            ".####.###...",
            ".#####.#....",
            "..####.#....",
            "......##....",
            "......#.....",
            ".....##.....",
            "....####....",
            "...######...",
        ),
        "nature" to listOf(
            "............",
            "............",
            ".....##.....",
            "....####....",
            "....#####...",
            "...##.####..",
            "..##...####.",
            "..#.....###.",
            ".##......##.",
            ".#........#.",
            "############",
            "............",
        ),
        "sky" to listOf(
            "............",
            "............",
            "....###.....",
            "...#####....",
            "..#######...",
            "..########..",
            ".##########.",
            "############",
            "############",
            ".##########.",
            "............",
            "............",
        ),
        "food" to listOf(
            "......#.....",
            ".....#......",
            "...##.##....",
            "..########..",
            ".##########.",
            ".##########.",
            ".##########.",
            ".##########.",
            "..########..",
            "..########..",
            "...##..##...",
            "............",
        ),
        "drink" to listOf(
            "............",
            ".##########.",
            ".#........#.",
            ".#........#.",
            ".##########.",
            ".##########.",
            "..########..",
            "..########..",
            "...######...",
            "...######...",
            "............",
            "............",
        ),
        "kitchen" to listOf(
            "............",
            "....####....",
            "..########..",
            "#.########.#",
            "############",
            "#.########.#",
            "..########..",
            "..########..",
            "..########..",
            "...######...",
            "............",
            "............",
        ),
        "furniture" to listOf(
            "..##........",
            "..##........",
            "..##........",
            "..##........",
            "..##........",
            "..########..",
            "..########..",
            "..##....##..",
            "..##....##..",
            "..##....##..",
            "..##....##..",
            "............",
        ),
        "home" to listOf(
            ".....##.....",
            "....####....",
            "...######...",
            "..########..",
            ".##########.",
            "..#......#..",
            "..#.##...#..",
            "..#.##.#.#..",
            "..#.##...#..",
            "..#.##...#..",
            "..########..",
            "............",
        ),
        "clothing" to listOf(
            "............",
            "..###..###..",
            ".####..####.",
            "############",
            "##.######.##",
            "...######...",
            "...######...",
            "...######...",
            "...######...",
            "...######...",
            "............",
            "............",
        ),
        "toy" to listOf(
            "............",
            ".##......##.",
            "####....####",
            ".##########.",
            "..########..",
            "..#.####.#..",
            "..########..",
            "..###..###..",
            "...######...",
            "....####....",
            "............",
            "............",
        ),
        "sport" to listOf(
            "............",
            ".##########.",
            "##.######.##",
            "#..######..#",
            "##.######.##",
            "..########..",
            "...######...",
            ".....##.....",
            ".....##.....",
            "...######...",
            "..########..",
            "............",
        ),
        "music" to listOf(
            "............",
            ".....#######",
            ".....#######",
            ".....#.....#",
            ".....#.....#",
            ".....#.....#",
            ".....#...###",
            "...###..####",
            "..####..###.",
            "..####......",
            "...##.......",
            "............",
        ),
        "art" to listOf(
            "..........##",
            ".........###",
            "........###.",
            ".......###..",
            "......###...",
            ".....###....",
            "....###.....",
            "...###......",
            "..###.......",
            ".##.........",
            ".#..........",
            "............",
        ),
        "tech" to listOf(
            "............",
            "############",
            "#..........#",
            "#..........#",
            "#..........#",
            "#..........#",
            "#..........#",
            "############",
            ".....##.....",
            "...######...",
            "............",
            "............",
        ),
        "tool" to listOf(
            "............",
            ".#######....",
            ".########...",
            ".#######....",
            "....##......",
            "....##......",
            "....##......",
            "....##......",
            "....##......",
            "....##......",
            "....##......",
            "............",
        ),
        "vehicle" to listOf(
            "............",
            "............",
            "...######...",
            "..##....##..",
            ".##......##.",
            "############",
            "############",
            "############",
            ".##......##.",
            ".##......##.",
            "............",
            "............",
        ),
        "place" to listOf(
            "............",
            "...######...",
            "...#.##.#...",
            "...######...",
            "...#.##.#...",
            "...######...",
            "...#.##.#...",
            "...######...",
            "...#.##.#...",
            "...##..##...",
            "...##..##...",
            "............",
        ),
    )
}
