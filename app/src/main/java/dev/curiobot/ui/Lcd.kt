package dev.curiobot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import dev.curiobot.brain.Mood
import kotlin.math.sin

enum class Phase { BOOT, DOWNLOADING, WARMING, IDLE, THINKING, CAMERA, ERROR }

/** Everything the screen shows. Replaced as a whole; never mutated. */
data class UiState(
    val phase: Phase = Phase.BOOT,
    val name: String = "",
    val satisfaction: Double = 0.0,
    val energy: Double = 0.0,
    val mood: Mood = Mood.CONTENT,
    val line: String = "",
    val options: List<String> = emptyList(),
    val cursor: Int = 0,
    val progress: Float = 0f,
    val status: String = "",
    val spriteSeed: Long = 0L,
    val asleep: Boolean = false,
    /** It has a wish today that hasn't come true yet: a small star by its name. */
    val wish: Boolean = false,
    /** A frozen, dithered photo shown while the creature looks at it. */
    val frozen: BooleanArray? = null,
    /** Header over the options inside a menu that Back leaves. Null on the home menu. */
    val title: String? = null,
    /** When set, the screen shows the nest instead of the creature. */
    val nest: NestView? = null,
    /** A nest kind's page: its token stands in for the creature. Level 0 draws an empty spot. */
    val token: String? = null,
    val tokenLevel: Int = 0,
    /** Hot and cold overlay on the viewfinder. */
    val hud: Hud? = null,
    /** The photo reveal: typed out while the text model thinks ("Is it a... banana!"). */
    val reveal: String? = null,
    val revealAt: Long = 0L,
    /** A banner for big moments (new kind, hunt done, found it), shown during the reveal. */
    val fanfare: String? = null,
    val fanfareKind: String? = null,
)

data class Hud(val label: String, val level: Double, val clue: String, val note: String? = null, val seconds: Int = 0)

/** Reveal timing, shared with the game so it can hold the screen long enough. */
object Reveal {
    const val MS_PER_CHAR = 45L
    const val HOLD_MS = 900L
    fun duration(text: String) = text.length * MS_PER_CHAR + HOLD_MS
}

/** The nest screen: a token per kind of thing, filled in as they're found. */
data class NestView(val levels: Map<String, Int>, val headline: String, val detail: String, val cursor: Int = -1)

/**
 * A 400x240 1-bit screen, like a Sharp Memory LCD: two tones only, no
 * anti-aliasing anywhere, so what you see is what the real panel could show.
 */
class Lcd {
    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val dark = Paint().apply { color = DARK; isAntiAlias = false; style = Paint.Style.FILL }
    private val light = Paint().apply { color = LIGHT; isAntiAlias = false; style = Paint.Style.FILL }
    private val text = Paint().apply {
        color = DARK; isAntiAlias = false; isSubpixelText = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 13f
    }
    private val small = Paint(text).apply { textSize = 11f }
    private val charW = text.measureText("M")
    private var sprite: CreatureSprite? = null
    private var spriteSeed = Long.MIN_VALUE
    private val framePixels = IntArray(W * H)

    fun render(ui: UiState, camera: BooleanArray?, tick: Int) {
        canvas.drawColor(LIGHT)
        when (ui.phase) {
            Phase.BOOT -> centered("...", 125, text)
            Phase.DOWNLOADING, Phase.WARMING -> egg(ui, tick)
            Phase.CAMERA -> viewfinder(ui, camera, tick)
            else -> home(ui, tick)
        }
    }

    private fun home(ui: UiState, tick: Int) {
        statusBar(ui)
        if (ui.phase == Phase.THINKING && ui.frozen != null) {
            drawFrame(ui.frozen)
            bar(0, H - 18, W, 18, dark)
            textAt(dots("looking", tick), 8, H - 5, small, light)
            return
        }
        ui.nest?.let { nest(it, ui.title); return }
        // creature
        if (sprite == null || spriteSeed != ui.spriteSeed) {
            sprite = CreatureSprite(ui.spriteSeed); spriteSeed = ui.spriteSeed
        }
        val s = sprite!!
        val blink = !ui.asleep && tick % 37 == 0
        val bob = when {
            ui.asleep -> 0
            ui.mood == Mood.DELIGHTED -> if (tick / 2 % 2 == 0) -6 else 0
            else -> if (tick / 5 % 2 == 0) 0 else 2
        }
        // Reveal: once the guess is typed out, react: hop when happy, shiver when wary.
        val now = System.currentTimeMillis()
        val revealing = ui.reveal != null && ui.phase == Phase.THINKING
        val typedFor = if (revealing) now - ui.revealAt - ui.reveal!!.length * Reveal.MS_PER_CHAR else -1L
        var dx = 0; var dy = 0
        if (revealing && typedFor in 0..1400) {
            val f = (typedFor / 100).toInt()
            when (ui.mood) {
                Mood.DELIGHTED, Mood.CURIOUS, Mood.FRIENDLY -> dy = intArrayOf(-4, -8, -10, -8, -4, 0, -4, -6, -4, 0, 0, 0, 0, 0, 0)[f]
                Mood.WARY -> dx = if (f % 2 == 0) -3 else 3
                else -> dy = if (f < 4) -4 else 0
            }
        }
        if (ui.token != null) bigToken(ui.token, ui.tokenLevel)
        else drawCreature(s, ui.mood, ui.asleep, blink, 14 + dx, 26 + (if (revealing) dy else bob), 6)
        if (revealing && ui.fanfare != null && typedFor >= 0) sparkles(tick)
        if (ui.asleep && ui.token == null) {
            val p = tick % 24
            textAt("z", 104, 40 - p / 3, small, dark)
            if (p > 8) textAt("Z", 112, 30 - (p - 8) / 3, text, dark)
        }
        // speech bubble
        val bx = 124; val by = 24; val bw = W - bx - 6; val bh = 96
        outline(bx, by, bw, bh)
        // little tail toward the creature
        bar(bx - 6, by + 40, 6, 2, dark); bar(bx - 3, by + 37, 3, 3, dark)
        val line = when {
            revealing -> ui.reveal!!.take(((now - ui.revealAt) / Reveal.MS_PER_CHAR).toInt().coerceAtLeast(0)) +
                (if (typedFor > 0) dots("", tick).let { if (it.isEmpty()) "" else " $it" } else "")
            ui.phase == Phase.THINKING -> dots("", tick)
            else -> ui.line
        }
        val maxChars = ((bw - 16) / charW).toInt()
        wrap(line, maxChars).take(5).forEachIndexed { i, l -> textAt(l, bx + 8, by + 18 + i * 16, text, dark) }
        header(ui.title, 124)
        if (revealing && ui.fanfare != null) fanfare(ui.fanfare, ui.fanfareKind, tick) else options(ui, top = 140)
        if (ui.phase == Phase.ERROR && ui.status.isNotEmpty()) textAt(ui.status, 8, H - 4, small, dark)
    }

    /** A big-moment banner where the options usually are, with the kind's token if there is one. */
    private fun fanfare(textLine: String, kind: String?, tick: Int) {
        val y = 146; val h = 46
        bar(4, y, W - 8, h, dark)
        // marching dots along the edge
        for (x in 8 until W - 8 step 8) if ((x / 8 + tick) % 2 == 0) { bar(x, y + 3, 3, 3, light); bar(x, y + h - 6, 3, 3, light) }
        var tx = 16
        kind?.let { k ->
            Pix.NEST[k]?.let { rows ->
                for (yy in rows.indices) for (xx in rows[yy].indices) if (rows[yy][xx] == '#') bar(16 + xx * 2, y + 11 + yy * 2, 2, 2, light)
            }
            tx = 48
        }
        val big = Paint(text).apply { textSize = 18f }
        textAt(textLine, tx, y + 30, big, light)
    }

    /** A few twinkling pixels around the creature. */
    private fun sparkles(tick: Int) {
        val spots = intArrayOf(10, 30, 108, 24, 6, 100, 112, 96, 60, 22, 100, 60, 20, 64)
        for (i in 0 until spots.size / 2) {
            if ((tick + i) % 3 == 0) continue
            val x = spots[i * 2]; val y = spots[i * 2 + 1]
            bar(x, y - 3, 1, 7, dark); bar(x - 3, y, 7, 1, dark)
        }
    }

    /** Up to four rows; longer lists scroll with the cursor and show arrows. */
    private fun options(ui: UiState, top: Int) {
        val rowH = 25
        val n = ui.options.size
        val first = if (n <= ROWS) 0 else (ui.cursor - (ROWS - 1)).coerceIn(0, n - ROWS)
        for (row in 0 until minOf(ROWS, n)) {
            val i = first + row
            val y = top + row * rowH
            val opt = ui.options[i]
            if (i == ui.cursor) {
                bar(4, y, W - 24, rowH - 3, dark)
                textAt("> $opt", 12, y + 16, text, light)
            } else {
                textAt("  $opt", 12, y + 16, text, dark)
            }
        }
        if (first > 0) arrow(W - 13, top + 4, up = true)
        if (first + ROWS < n) arrow(W - 13, top + ROWS * rowH - 12, up = false)
    }

    private fun arrow(x: Int, y: Int, up: Boolean) {
        for (r in 0 until 5) {
            val w = if (up) r * 2 + 1 else (4 - r) * 2 + 1
            bar(x + 4 - w / 2, y + r, w, 1, dark)
        }
    }

    /** A tab with where you are, and a reminder that Back leaves it. */
    private fun header(title: String?, y: Int) {
        if (title == null) return
        val tw = small.measureText(title).toInt()
        bar(4, y, tw + 12, 14, dark)
        textAt(title, 10, y + 11, small, light)
        val hint = "back to leave"
        val hx = W - 8 - small.measureText(hint).toInt()
        var x = tw + 22
        while (x < hx - 6) { bar(x, y + 7, 2, 1, dark); x += 4 }
        textAt(hint, hx, y + 11, small, dark)
    }

    /** A kind's token, large, with its level pips, where the creature usually stands. */
    private fun bigToken(kind: String, level: Int) {
        val k = 6; val size = 12 * k
        val x0 = (124 - size) / 2; val y0 = 28
        if (level == 0) {
            var d = 0
            while (d < size) {
                bar(x0 + d, y0, 3, 2, dark); bar(x0 + d, y0 + size - 2, 3, 2, dark)
                bar(x0, y0 + d, 2, 3, dark); bar(x0 + size - 2, y0 + d, 2, 3, dark)
                d += 7
            }
            centeredIn("?", x0, size, y0 + size / 2 + 5, text)
        } else {
            Pix.NEST[kind]?.let { rows ->
                for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] == '#') bar(x0 + x * k, y0 + y * k, k, k, dark)
            }
        }
        val px0 = x0 + (size - 37) / 2
        for (p in 0 until 3) {
            val px = px0 + p * 14
            if (p < level) bar(px, y0 + size + 6, 9, 9, dark) else outline1(px, y0 + size + 6, 9, 9)
        }
    }

    private fun centeredIn(s: String, x: Int, w: Int, y: Int, p: Paint) {
        textAt(s, x + ((w - p.measureText(s)) / 2).toInt(), y, p, dark)
    }

    private fun nest(v: NestView, title: String?) {
        textAt(v.headline, 8, 36, text, dark)
        val cw = 56; val ch = 48; val x0 = 4; val y0 = 42
        for ((i, kind) in dev.curiobot.brain.Nest.KINDS.withIndex()) {
            val cx = x0 + (i % 7) * cw
            val cy = y0 + (i / 7) * ch
            val level = v.levels[kind] ?: 0
            val ix = cx + 10; val iy = cy + 1
            if (level == 0) {
                // an empty spot, still to be found
                var d = 0
                while (d < 36) {
                    bar(ix + d, iy, 2, 1, dark); bar(ix + d, iy + 35, 2, 1, dark)
                    bar(ix, iy + d, 1, 2, dark); bar(ix + 35, iy + d, 1, 2, dark)
                    d += 5
                }
            } else {
                Pix.NEST[kind]?.let { rows ->
                    for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] == '#') bar(ix + x * 3, iy + y * 3, 3, 3, dark)
                }
            }
            for (p in 0 until 3) {
                val px = cx + 17 + p * 8
                if (p < level) bar(px, cy + 40, 5, 5, dark) else if (level > 0) outline1(px, cy + 40, 5, 5)
            }
            if (i == v.cursor) outline(cx + 4, cy - 3, 48, 51)
        }
        wrap(v.detail, ((W - 16) / small.measureText("M")).toInt()).take(1).forEach { textAt(it, 8, 204, small, dark) }
        header(title, 220)
    }

    private fun statusBar(ui: UiState) {
        textAt(ui.name, 6, 13, text, dark)
        if (ui.wish) icon(Pix.STAR, 10 + text.measureText(ui.name).toInt(), 4)
        // Two labeled meters, right-aligned: fun fills as it sees new things, energy drops as it looks.
        val mw = 48
        val eMeter = W - 6 - mw
        val eLabel = eMeter - 5 - small.measureText("energy").toInt()
        val fMeter = eLabel - 12 - mw
        val fLabel = fMeter - 5 - small.measureText("fun").toInt()
        textAt("fun", fLabel, 12, small, dark)
        meter(fMeter, 6, mw, ui.satisfaction)
        textAt("energy", eLabel, 12, small, dark)
        meter(eMeter, 6, mw, ui.energy)
        bar(0, 18, W, 2, dark)
    }

    private fun egg(ui: UiState, tick: Int) {
        val wobble = (sin(tick / 2.0) * 3).toInt()
        val x0 = W / 2 - 48 + wobble
        val y0 = 18
        for (y in 0 until 16) for (x in 0 until 16) if (Pix.EGG[y][x] == '#') bar(x0 + x * 6, y0 + y * 6, 6, 6, dark)
        if (ui.phase == Phase.WARMING || ui.progress > 0.8f) {
            for ((y, x) in Pix.CRACK) bar(x0 + x * 6, y0 + y * 6, 6, 6, dark)
        }
        centered(if (ui.phase == Phase.WARMING) dots("Waking up", tick) else "Hatching... ${(ui.progress * 100).toInt()}%", 135, text)
        if (ui.phase == Phase.DOWNLOADING) {
            outline(50, 148, W - 100, 14)
            bar(53, 151, ((W - 106) * ui.progress).toInt(), 8, dark)
        }
        if (ui.status.isNotEmpty()) wrap(ui.status, 54).take(3).forEachIndexed { i, l -> centered(l, 186 + i * 14, small) }
    }

    private fun viewfinder(ui: UiState, frame: BooleanArray?, tick: Int) {
        if (frame != null) drawFrame(frame) else centered(dots("opening eyes", tick), 120, text)
        val hud = ui.hud
        if (hud != null) {
            // Hot and cold: the meter on top, the clue under it.
            bar(0, 0, W, 22, dark)
            val big = Paint(text).apply { textSize = 16f }
            textAt(hud.label, 8, 17, big, light)
            val mx = 150; val mw = W - mx - 52
            bar(mx, 5, mw, 12, light)
            bar(mx + 2, 7, ((mw - 4) * hud.level).toInt(), 8, dark)
            textAt("${hud.seconds}s", W - 44, 16, small, light)
            bar(0, 22, W, 16, light); bar(0, 37, W, 1, dark)
            textAt(hud.note ?: hud.clue, 8, 34, small, dark)
        }
        bar(0, H - 20, W, 20, dark)
        val ok = if (hud != null) "(o) is it this?" else "(o) show it"
        textAt(ok, ((W - text.measureText(ok)) / 2).toInt(), H - 6, text, light)
        val back = if (hud != null) "give up" else "back"
        textAt(back, W - 8 - (text.measureText(back)).toInt(), H - 6, text, light)
    }

    private fun drawFrame(frame: BooleanArray) {
        for (i in framePixels.indices) framePixels[i] = if (frame[i]) DARK else LIGHT
        bitmap.setPixels(framePixels, 0, W, 0, 0, W, H)
    }

    private fun drawCreature(s: CreatureSprite, mood: Mood, asleep: Boolean, blink: Boolean, x0: Int, y0: Int, k: Int) {
        for (y in 0 until 16) for (x in 0 until 16) if (s.body[y][x]) bar(x0 + x * k, y0 + y * k, k, k, dark)
        for ((y, x) in s.face(mood, asleep, blink)) if (y in 0..15 && x in 0..15) bar(x0 + x * k, y0 + y * k, k, k, light)
    }

    private fun icon(rows: List<String>, x0: Int, y0: Int) {
        for (y in rows.indices) for (x in rows[y].indices) if (rows[y][x] == '#') bar(x0 + x, y0 + y, 1, 1, dark)
    }

    private fun meter(x: Int, y: Int, w: Int, v: Double) {
        outline(x, y, w, 7)
        bar(x + 2, y + 2, ((w - 4) * v.coerceIn(0.0, 1.0)).toInt(), 3, dark)
    }

    private fun outline1(x: Int, y: Int, w: Int, h: Int) {
        bar(x, y, w, 1, dark); bar(x, y + h - 1, w, 1, dark)
        bar(x, y, 1, h, dark); bar(x + w - 1, y, 1, h, dark)
    }

    private fun outline(x: Int, y: Int, w: Int, h: Int) {
        bar(x, y, w, 2, dark); bar(x, y + h - 2, w, 2, dark)
        bar(x, y, 2, h, dark); bar(x + w - 2, y, 2, h, dark)
    }

    private fun bar(x: Int, y: Int, w: Int, h: Int, p: Paint) {
        if (w > 0 && h > 0) canvas.drawRect(Rect(x, y, x + w, y + h), p)
    }

    private fun textAt(s: String, x: Int, y: Int, p: Paint, color: Paint) {
        p.color = color.color
        canvas.drawText(s, x.toFloat(), y.toFloat(), p)
        p.color = DARK
    }

    private fun centered(s: String, y: Int, p: Paint) {
        textAt(s, ((W - p.measureText(s)) / 2).toInt(), y, p, dark)
    }

    private fun dots(prefix: String, tick: Int) = prefix + ".".repeat(tick / 3 % 4)

    companion object {
        const val W = 400
        const val H = 240
        const val ROWS = 4
        const val LIGHT = 0xFFC9CCC2.toInt()
        const val DARK = 0xFF1E2120.toInt()

        fun wrap(s: String, max: Int): List<String> {
            val out = mutableListOf<String>()
            var cur = StringBuilder()
            for (w in s.split(' ')) {
                if (cur.isNotEmpty() && cur.length + 1 + w.length > max) { out += cur.toString(); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(w)
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }

        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

        /**
         * Turn a camera frame into 1-bit pixels with ordered dithering. The
         * frame is center-cropped to the screen's 5:3 shape. True = dark.
         */
        fun dither(src: Bitmap): BooleanArray {
            val targetRatio = W.toFloat() / H
            val srcRatio = src.width.toFloat() / src.height
            val (cw, ch) = if (srcRatio > targetRatio) (src.height * targetRatio).toInt() to src.height
            else src.width to (src.width / targetRatio).toInt()
            val crop = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
            val small = Bitmap.createScaledBitmap(crop, W, H, true)
            val px = IntArray(W * H)
            small.getPixels(px, 0, W, 0, 0, W, H)
            if (small !== crop) small.recycle()
            if (crop !== src) crop.recycle()
            val out = BooleanArray(W * H)
            for (y in 0 until H) for (x in 0 until W) {
                val c = px[y * W + x]
                val g = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                val t = (BAYER[(y and 3) * 4 + (x and 3)] * 16 + 8)
                out[y * W + x] = g < t
            }
            return out
        }
    }
}

/** Shows the LCD scaled up with hard pixel edges, redrawn about 10 times a second. */
class LcdView(ctx: Context, private val source: () -> Pair<UiState, BooleanArray?>) : View(ctx) {
    private val lcd = Lcd()
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private var tick = 0
    private val dst = Rect()
    private val frame = object : Runnable {
        override fun run() {
            tick++
            invalidate()
            postDelayed(this, 100)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(frame) }
    override fun onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * Lcd.H / Lcd.W)
    }

    override fun onDraw(c: Canvas) {
        val (ui, cam) = source()
        lcd.render(ui, cam, tick)
        dst.set(0, 0, width, height)
        c.drawBitmap(lcd.bitmap, null, dst, paint)
    }
}
