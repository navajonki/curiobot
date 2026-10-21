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
    /** A frozen, dithered photo shown while the creature looks at it. */
    val frozen: BooleanArray? = null,
)

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
            Phase.CAMERA -> viewfinder(camera, tick)
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
        drawCreature(s, ui.mood, ui.asleep, blink, 14, 26 + bob, 6)
        if (ui.asleep) {
            val p = tick % 24
            textAt("z", 104, 40 - p / 3, small, dark)
            if (p > 8) textAt("Z", 112, 30 - (p - 8) / 3, text, dark)
        }
        // speech bubble
        val bx = 124; val by = 22; val bw = W - bx - 6; val bh = 110
        outline(bx, by, bw, bh)
        // little tail toward the creature
        bar(bx - 6, by + 40, 6, 2, dark); bar(bx - 3, by + 37, 3, 3, dark)
        val line = if (ui.phase == Phase.THINKING) dots("", tick) else ui.line
        val maxChars = ((bw - 16) / charW).toInt()
        wrap(line, maxChars).take(6).forEachIndexed { i, l -> textAt(l, bx + 8, by + 18 + i * 16, text, dark) }
        // options
        val top = 138
        val rowH = 25
        ui.options.forEachIndexed { i, opt ->
            val y = top + i * rowH
            if (i == ui.cursor) {
                bar(4, y, W - 8, rowH - 3, dark)
                textAt("> $opt", 12, y + 16, text, light)
            } else {
                textAt("  $opt", 12, y + 16, text, dark)
            }
        }
        if (ui.phase == Phase.ERROR && ui.status.isNotEmpty()) textAt(ui.status, 8, H - 4, small, dark)
    }

    private fun statusBar(ui: UiState) {
        textAt(ui.name, 6, 13, text, dark)
        icon(Pix.EYE, 236, 5)
        meter(246, 6, 54, ui.satisfaction)
        icon(Pix.BOLT, 316, 5)
        meter(324, 6, 54, ui.energy)
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

    private fun viewfinder(frame: BooleanArray?, tick: Int) {
        if (frame != null) drawFrame(frame) else centered(dots("opening eyes", tick), 120, text)
        bar(0, H - 20, W, 20, dark)
        textAt("< back", 8, H - 6, text, light)
        textAt("(o) show it", W - 8 - (text.measureText("(o) show it")).toInt(), H - 6, text, light)
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
