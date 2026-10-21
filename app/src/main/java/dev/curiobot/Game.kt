package dev.curiobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.curiobot.brain.CreatureState
import dev.curiobot.brain.EmbeddingMemory
import dev.curiobot.brain.Event
import dev.curiobot.brain.Intent
import dev.curiobot.brain.Lines
import dev.curiobot.brain.Mind
import dev.curiobot.brain.Mood
import dev.curiobot.brain.Pace
import dev.curiobot.brain.Prompt
import dev.curiobot.brain.Safety
import dev.curiobot.brain.Seeing
import dev.curiobot.brain.Verdict
import dev.curiobot.brain.allOk
import dev.curiobot.brain.failures
import dev.curiobot.log.Prefs
import dev.curiobot.log.Telemetry
import dev.curiobot.text.ModelStore
import dev.curiobot.text.TextModel
import dev.curiobot.ui.Lcd
import dev.curiobot.ui.Phase
import dev.curiobot.ui.UiState
import dev.curiobot.vision.Eye
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Runs the creature: owns its state, turns button presses into actions, asks
 * the eye and the text model for help, checks everything the model writes, and
 * logs each step. All state changes happen on the main thread.
 */
class Game(private val ctx: Context) {
    interface Host {
        fun startCamera()
        fun stopCamera()
        fun ensureCameraPermission(onResult: (Boolean) -> Unit)
        fun haptic()
    }

    enum class Button { LEFT, SELECT, RIGHT }

    var host: Host? = null

    @Volatile var ui = UiState(); private set
    /** Latest dithered camera frame, written by the camera thread. */
    @Volatile var cameraFrame: BooleanArray? = null; private set
    @Volatile var lastTurn: JSONObject? = null; private set

    private val scope: CoroutineScope = MainScope()
    private val textThread = Executors.newSingleThreadExecutor { Thread(it, "text-model") }.asCoroutineDispatcher()
    private val dir = File(ctx.filesDir, "creature").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val memory = EmbeddingMemory(File(dir, "sightings.bin"))
    private val rng = Random(System.nanoTime())

    private lateinit var state: CreatureState
    private var eye: Eye? = null
    private var text: TextModel? = null
    private var pace = Pace(Prefs.testPace)
    private var intents: List<Intent> = emptyList()
    private var mood = Mood.CONTENT
    private var newborn = false
    private var gameAnswer = 0
    private var ticker: Job? = null
    private val frameLock = Any()
    private var lastFrame: Bitmap? = null
    private var started = false

    val textBackend get() = text?.backendName ?: "none"
    val eyeReady get() = eye != null

    // ---------- lifecycle ----------

    fun start() {
        if (started) return
        started = true
        loadState()
        memory.load()
        scope.launch {
            val t = System.currentTimeMillis()
            eye = withContext(Dispatchers.Default) {
                runCatching { Eye(ctx) }.onFailure { Telemetry.error("eye_init_failed", it) }.getOrNull()
            }
            Telemetry.log("eye_init", JSONObject().put("ok", eye != null).put("ms", System.currentTimeMillis() - t)
                .put("vocabulary", eye?.vocabularySize ?: 0))
        }
        scope.launch { prepareTextModel() }
    }

    fun resume() {
        pace = Pace(Prefs.testPace)
        if (!::state.isInitialized) return
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tickNeeds()
                delay(10_000)
            }
        }
    }

    fun pause() {
        ticker?.cancel()
        if (ui.phase == Phase.CAMERA) { closeCamera(log = "app_paused"); backToIdle() }
        if (::state.isInitialized) saveState()
        Telemetry.flushSoon()
    }

    // ---------- model setup ----------

    private suspend fun prepareTextModel() {
        if (!ModelStore.isReady(ctx)) {
            show(UiState(Phase.DOWNLOADING, status = "First time only: downloading the creature's words (347 MB). Wi-Fi helps."))
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ModelStore.download(ctx) { p -> ui = ui.copy(progress = p) }
                }.onFailure { Telemetry.error("model_download_failed", it) }.isSuccess
            }
            if (!ok) {
                intents = listOf(Intent.TRY_AGAIN)
                show(UiState(Phase.ERROR, line = "I couldn't finish hatching. Is the internet on?",
                    options = listOf("Try again"), status = "Downloads resume where they stopped."))
                return
            }
        }
        show(UiState(Phase.WARMING))
        val tm = TextModel(ModelStore.file(ctx).absolutePath, ctx.cacheDir.absolutePath)
        withContext(textThread) { tm.init(Prefs.backend) }
        text = if (tm.ready) tm else null
        if (text == null) Telemetry.warn("text_model_unavailable", JSONObject().put("note", "using written fallback lines"))
        greet()
    }

    private fun greet() {
        val now = System.currentTimeMillis()
        val woke = Mind.tick(state, now, pace)
        if (newborn) {
            newborn = false
            mood = Mood.CURIOUS
            intents = Mind.intentsFor(state, fresh = false)
            Telemetry.log("hatched", traitsJson())
            showIdle(Lines.pick(Lines.HATCH, rng.nextInt(), name = state.traits.name), labelsFor(intents))
        } else if (woke) {
            Telemetry.log("wake", state.summary())
            mood = Mind.idleMood(state)
            turn(Event.Woke, fresh = false)
        } else {
            mood = Mind.idleMood(state)
            intents = Mind.intentsFor(state, fresh = false)
            val line = if (state.asleep) Lines.pick(Lines.LET_SLEEP, rng.nextInt())
            else Lines.pick(Lines.IDLE[mood] ?: Lines.IDLE.getValue(Mood.CURIOUS), rng.nextInt())
            showIdle(line, labelsFor(intents))
        }
        resume()
    }

    // ---------- input ----------

    fun press(b: Button) {
        host?.haptic()
        Telemetry.log("button", JSONObject().put("button", b.name.lowercase()).put("phase", ui.phase.name.lowercase())
            .put("cursor", ui.cursor).put("options", JSONArray(ui.options)))
        when (ui.phase) {
            Phase.IDLE, Phase.ERROR -> when (b) {
                Button.LEFT -> moveCursor(-1)
                Button.RIGHT -> moveCursor(1)
                Button.SELECT -> intents.getOrNull(ui.cursor)?.let { perform(it) }
            }
            Phase.CAMERA -> when (b) {
                Button.LEFT -> closeCamera(log = "cancelled").also { backToIdle() }
                Button.SELECT, Button.RIGHT -> snap()
            }
            else -> {}
        }
    }

    private fun moveCursor(d: Int) {
        val n = ui.options.size
        if (n == 0) return
        show(ui.copy(cursor = Math.floorMod(ui.cursor + d, n)))
    }

    private fun perform(intent: Intent) {
        tickNeeds()
        Telemetry.log("intent", JSONObject().put("intent", intent.id).put("creature", state.summary()))
        when (intent) {
            Intent.SHOW_NEW -> openCamera()
            Intent.LOOK_CLOSER -> state.focusWord?.let { Mind.spend(state, intent); turn(Event.Closer(it), fresh = true) } ?: backToIdle()
            Intent.ASK_ABOUT -> state.focusWord?.let { Mind.spend(state, intent); turn(Event.About(it), fresh = true) } ?: backToIdle()
            Intent.SAY_HI -> { Mind.spend(state, intent); mood = Mood.FRIENDLY; turn(Event.Hello, fresh = false) }
            Intent.REMEMBER -> {
                val e = Mind.pickMemory(state, rng.nextInt(1000))
                if (e == null) backToIdle() else { Mind.spend(state, intent); mood = Mood.CONTENT; turn(Event.Recall(e), fresh = false) }
            }
            Intent.PLAY -> {
                gameAnswer = rng.nextInt(2)
                intents = listOf(Intent.GUESS_LEFT, Intent.GUESS_RIGHT)
                mood = Mood.CURIOUS
                Telemetry.log("game_start", JSONObject())
                showIdle(Lines.pick(Lines.GAME_START, rng.nextInt()), labelsFor(intents))
            }
            Intent.GUESS_LEFT, Intent.GUESS_RIGHT -> {
                val win = (if (intent == Intent.GUESS_LEFT) 0 else 1) == gameAnswer
                Mind.spend(state, intent)
                mood = if (win) Mood.DELIGHTED else Mood.CONTENT
                Telemetry.log("game_round", JSONObject().put("win", win))
                intents = afterTurnIntents(fresh = false)
                showIdle(Lines.pick(if (win) Lines.GAME_WIN else Lines.GAME_LOSE, rng.nextInt()), labelsFor(intents))
                saveState()
            }
            Intent.NAP -> {
                Mind.startNap(state, System.currentTimeMillis(), pace)
                mood = Mood.SLEEPY
                intents = Mind.intentsFor(state, fresh = false)
                Telemetry.log("nap_start", JSONObject().put("minutes", pace.napMs / 60_000))
                showIdle(Lines.pick(Lines.NAP, rng.nextInt()), labelsFor(intents))
                saveState()
            }
            Intent.LET_SLEEP -> showIdle(Lines.pick(Lines.LET_SLEEP, rng.nextInt()), labelsFor(intents))
            Intent.PEEK -> {
                state.dreamShown = true
                val word = state.seen.keys.lastOrNull { it != "person" }
                intents = Mind.intentsFor(state, fresh = false)
                showIdle(if (word == null) Lines.pick(Lines.DREAM_EMPTY, 0) else Lines.pick(Lines.DREAM, rng.nextInt(), word),
                    labelsFor(intents))
            }
            Intent.WAKE -> {
                val progress = Mind.wakeEarly(state, System.currentTimeMillis(), pace)
                mood = Mood.SLEEPY
                intents = Mind.intentsFor(state, fresh = false)
                Telemetry.log("wake_early", JSONObject().put("nap_progress", Math.round(progress * 100) / 100.0)
                    .put("energy", Math.round(state.energy * 100) / 100.0))
                showIdle(Lines.pick(Lines.WOKE_EARLY, rng.nextInt()), labelsFor(intents))
                saveState()
            }
            Intent.TRY_AGAIN -> scope.launch { prepareTextModel() }
        }
    }

    // ---------- camera ----------

    private fun openCamera() {
        val h = host ?: return
        h.ensureCameraPermission { granted ->
            if (granted) {
                cameraFrame = null
                show(ui.copy(phase = Phase.CAMERA))
                h.startCamera()
                Telemetry.log("camera_open", JSONObject())
            } else {
                Telemetry.warn("camera_denied", JSONObject())
                intents = Mind.intentsFor(state, fresh = false)
                showIdle(Lines.pick(Lines.NO_CAMERA, 0), labelsFor(intents))
            }
        }
    }

    /** Called on the camera thread with each frame (already upright). */
    fun onCameraFrame(frame: Bitmap) {
        val dithered = Lcd.dither(frame)
        synchronized(frameLock) {
            lastFrame?.recycle()
            lastFrame = frame
        }
        cameraFrame = dithered
    }

    private fun closeCamera(log: String) {
        host?.stopCamera()
        Telemetry.log("camera_close", JSONObject().put("why", log))
    }

    private fun snap() {
        val photo = synchronized(frameLock) { lastFrame?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) } }
        if (photo == null) return
        val frozen = cameraFrame
        closeCamera(log = "snap")
        show(ui.copy(phase = Phase.THINKING, frozen = frozen))
        val e = eye
        if (e == null) {
            Telemetry.warn("eye_not_ready", JSONObject())
            backToIdle()
            return
        }
        scope.launch {
            val p = withContext(Dispatchers.Default) {
                runCatching { e.see(photo) }.onFailure { Telemetry.error("perception_failed", it) }.getOrNull()
            }
            photo.recycle()
            if (p == null) backToIdle() else handlePerception(p)
        }
    }

    private fun handlePerception(p: Eye.Perception) {
        Telemetry.log("perception", p.toLog())
        when (p.verdict) {
            Verdict.TRIPWIRE -> {
                // Look away; nothing is remembered, named, or described.
                Telemetry.log("tripwire", JSONObject().put("group", p.tripGroup).put("score", p.tripScore))
                mood = Mood.QUIET
                intents = Mind.intentsFor(state, fresh = false)
                showIdle(Lines.pick(Lines.LOOK_AWAY, rng.nextInt()), labelsFor(intents))
            }
            Verdict.LOWINFO -> {
                mood = Mood.PUZZLED
                intents = Mind.intentsFor(state, fresh = false)
                showIdle(Lines.pick(Lines.LOWINFO[p.label] ?: Lines.LOWINFO.getValue("blank"), rng.nextInt()), labelsFor(intents))
            }
            Verdict.UNSURE -> {
                mood = Mood.PUZZLED
                intents = Mind.intentsFor(state, fresh = false)
                showIdle(Lines.pick(Lines.UNSURE, rng.nextInt(), word = p.label), labelsFor(intents))
            }
            Verdict.NORMAL, Verdict.PERSON -> {
                val person = p.verdict == Verdict.PERSON
                val novelty = memory.novelty(p.embedding)
                // Photos of people are never kept, not even as embeddings.
                if (!person) { memory.add(p.embedding); memory.save() }
                val seeing = Seeing(p.verdict, p.label, p.cat, p.confidence, novelty)
                val r = Mind.react(state, seeing, System.currentTimeMillis())
                mood = r.mood
                Telemetry.log("reaction", JSONObject().apply {
                    put("word", if (person) "person" else p.label)
                    put("mood", r.mood.word); put("first_time", r.firstTime); put("count", r.count)
                    put("score", Math.round(r.score * 100) / 100.0); put("image_novelty", Math.round(novelty * 100) / 100.0)
                    put("satisfaction_delta", Math.round(r.satisfactionDelta * 100) / 100.0)
                    put("energy_delta", Math.round(r.energyDelta * 100) / 100.0)
                    put("journal", r.journal?.text ?: JSONObject.NULL)
                })
                val ev = if (person) Event.SawPerson(r.firstTime) else Event.Saw(p.label, r.firstTime, r.count)
                turn(ev, fresh = true)
            }
        }
    }

    // ---------- turns ----------

    /** Ask the text model for a line + labels, check them, fall back if needed. */
    private fun turn(event: Event, fresh: Boolean) {
        val next = afterTurnIntents(fresh)
        intents = next
        show(ui.copy(phase = Phase.THINKING, frozen = null, line = ""))
        val traits = state.traits
        val m = mood
        scope.launch {
            val result = withContext(textThread) { compose(traits, m, event, next) }
            lastTurn = result.log
            Telemetry.log("turn", result.log)
            showIdle(result.line, result.labels)
            saveState()
        }
    }

    private fun afterTurnIntents(fresh: Boolean): List<Intent> =
        if (Mind.shouldDoze(state)) listOf(Intent.NAP).also { Telemetry.log("dozing_cue", state.summary()) }
        else Mind.intentsFor(state, fresh)

    data class Composed(val line: String, val labels: List<String>, val log: JSONObject)

    private fun compose(traits: dev.curiobot.brain.Traits, m: Mood, event: Event, next: List<Intent>): Composed {
        val t0 = System.currentTimeMillis()
        val spec = Prompt.build(traits, m, event, next)
        val modelIntents = next.filter { it.modelLabel }
        val attempts = JSONArray()
        var line: String? = null
        var lineSource = "fallback"
        var modelLabels: List<String?> = List(modelIntents.size) { null }
        val tm = text
        if (tm != null) {
            for (attempt in 1..2) {
                val temp = if (attempt == 1) 0.8 else 0.6
                val seed = rng.nextInt(1, Int.MAX_VALUE)
                val out = runCatching { tm.write(spec, temp, seed) }
                    .onFailure { Telemetry.error("text_model_failed", it) }.getOrNull() ?: break
                val parsed = Prompt.parse(out.raw, modelIntents.size)
                val checks = parsed.line?.let { Safety.checkLine(it, spec.requiredWord, spec.aboutPerson) }
                attempts.put(JSONObject().apply {
                    put("attempt", attempt); put("temperature", temp); put("seed", seed); put("ms", out.ms)
                    put("raw", out.raw); put("well_formed", parsed.wellFormed)
                    put("line_failures", JSONArray(checks?.failures() ?: listOf("no_line")))
                })
                if (parsed.labels.any { it != null }) modelLabels = parsed.labels
                if (checks != null && checks.allOk()) {
                    line = parsed.line
                    lineSource = if (attempt == 1) "model" else "model_retry"
                    modelLabels = parsed.labels
                    break
                }
            }
        }
        val finalLine = line ?: fallbackLine(m, event, traits.name)
        // Labels: the model's if they pass, else the written one for that intent.
        val labelLog = JSONArray()
        var mi = 0
        val labels = next.mapIndexed { i, intent ->
            val written = intent.fallbackLabel(state.focusWord, rng.nextInt(10))
            if (!intent.modelLabel) { labelLog.put(JSONObject().put("intent", intent.id).put("label", written).put("source", "fixed")); return@mapIndexed written }
            val cand = modelLabels.getOrNull(mi++)
            val fails = cand?.let { Safety.checkLabel(it).failures() } ?: listOf("missing")
            val use = if (cand != null && fails.isEmpty()) cand else written
            labelLog.put(JSONObject().put("intent", intent.id).put("label", use)
                .put("source", if (use == cand) "model" else "written").put("failures", JSONArray(if (use == cand) emptyList() else fails)))
            use
        }
        val log = JSONObject().apply {
            put("event", event.promptLine())
            put("mood", m.word)
            put("prompt_user", spec.user)
            put("required_word", spec.requiredWord ?: JSONObject.NULL)
            put("about_person", spec.aboutPerson)
            put("attempts", attempts)
            put("line", finalLine)
            put("line_source", lineSource)
            put("labels", labelLog)
            put("text_backend", tm?.backendName ?: "none")
            put("total_ms", System.currentTimeMillis() - t0)
            put("creature", state.summary())
        }
        return Composed(finalLine, labels, log)
    }

    private fun fallbackLine(m: Mood, event: Event, name: String): String {
        val word = event.requiredWord
        return when {
            event.aboutPerson -> Lines.pick(Lines.FALLBACK.getValue(Mood.FRIENDLY), rng.nextInt())
            event is Event.Woke -> Lines.pick(Lines.WOKE, rng.nextInt())
            word != null -> Lines.pick(Lines.FALLBACK[m] ?: Lines.FALLBACK.getValue(Mood.CURIOUS), rng.nextInt(), word, name)
            else -> Lines.pick(Lines.IDLE[m] ?: Lines.IDLE.getValue(Mood.CURIOUS), rng.nextInt())
        }
    }

    private fun labelsFor(list: List<Intent>) = list.map { it.fallbackLabel(state.focusWord, 0) }

    // ---------- needs & display ----------

    private fun tickNeeds() {
        if (!::state.isInitialized) return
        val woke = Mind.tick(state, System.currentTimeMillis(), pace)
        if (woke && ui.phase == Phase.IDLE) {
            Telemetry.log("wake", state.summary())
            mood = Mind.idleMood(state)
            turn(Event.Woke, fresh = false)
            return
        }
        if (ui.phase == Phase.IDLE) show(ui.copy(satisfaction = state.satisfaction, energy = state.energy, asleep = state.asleep))
    }

    private fun backToIdle() {
        mood = Mind.idleMood(state)
        intents = Mind.intentsFor(state, fresh = false)
        showIdle(Lines.pick(Lines.IDLE[mood] ?: Lines.IDLE.getValue(Mood.CURIOUS), rng.nextInt()), labelsFor(intents))
    }

    private fun showIdle(line: String, labels: List<String>) {
        show(UiState(
            phase = Phase.IDLE, name = state.traits.name, satisfaction = state.satisfaction, energy = state.energy,
            mood = if (state.asleep) Mood.SLEEPY else mood, line = line, options = labels, cursor = 0,
            spriteSeed = state.seed, asleep = state.asleep,
        ))
        Telemetry.log("screen", JSONObject().put("line", line).put("options", JSONArray(labels))
            .put("mood", mood.word).put("asleep", state.asleep))
    }

    private fun show(s: UiState) {
        ui = if (::state.isInitialized && s.name.isEmpty()) s.copy(name = state.traits.name, spriteSeed = state.seed,
            satisfaction = state.satisfaction, energy = state.energy, asleep = state.asleep) else s
    }

    // ---------- persistence ----------

    private fun loadState() {
        val loaded = runCatching { if (stateFile.exists()) CreatureState.fromJson(JSONObject(stateFile.readText())) else null }
            .onFailure { Telemetry.error("state_load_failed", it) }.getOrNull()
        if (loaded != null) {
            state = loaded
            Telemetry.log("creature_loaded", traitsJson().put("state", state.summary()))
        } else {
            val seed = Random.nextLong()
            state = CreatureState(seed, System.currentTimeMillis())
            newborn = true
            Telemetry.log("creature_new", traitsJson())
            saveState()
        }
    }

    private fun saveState() {
        runCatching {
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(state.toJson().toString())
            tmp.renameTo(stateFile)
        }.onFailure { Telemetry.error("state_save_failed", it) }
    }

    private fun traitsJson(): JSONObject {
        val t = state.traits
        return JSONObject().apply {
            put("seed", t.seed); put("name", t.name); put("temperament", t.temperament); put("style", t.style)
            put("loves", JSONArray(t.loves)); put("dislikes", t.dislikes)
            put("curiosity", Math.round(t.curiosity * 100) / 100.0); put("shyness", Math.round(t.shyness * 100) / 100.0)
        }
    }

    // ---------- dev panel ----------

    fun statusText(): String = buildString {
        appendLine("device   ${Telemetry.deviceId.take(8)}  session ${Telemetry.sessionId}")
        appendLine("build    ${Telemetry.build}")
        appendLine("text     ${textBackend}  model file ${if (ModelStore.isReady(ctx)) "ready" else "missing"}")
        appendLine("eye      ${if (eyeReady) "ready" else "not ready"}  memories ${memory.size}")
        appendLine("logs     upload ${if (Telemetry.uploadEnabled) "on" else "off"}, pending ${Telemetry.pendingCount()}, sent ${Telemetry.uploadedTotal}" +
            (Telemetry.lastUploadError?.let { "\n         last error: $it" } ?: ""))
        appendLine("pace     ${if (pace.test) "test (hours are minutes)" else "real"}")
        if (::state.isInitialized) {
            val t = state.traits
            appendLine("creature ${t.name} (${t.temperament}, ${t.style})")
            appendLine("         loves ${t.loves.joinToString()}, dislikes ${t.dislikes}")
            appendLine("         curiosity %.2f  energy %.2f  words %d  asleep %s".format(state.satisfaction, state.energy, state.seen.size, state.asleep))
        }
    }

    fun resetCreature() {
        Telemetry.log("creature_reset", traitsJson())
        stateFile.delete()
        memory.clear()
        loadState()
        greet()
    }

    /**
     * Runs the bundled test photos through the eye and the text model without
     * touching the real creature. Results go to the logs (event "selftest").
     */
    fun runSelfTest(done: (String) -> Unit) {
        val e = eye
        scope.launch {
            val names = ctx.assets.list("testphotos")?.sorted() ?: emptyList()
            val summary = StringBuilder()
            val scratch = CreatureState(state.seed, state.bornAt)
            for (n in names) {
                val bmp = ctx.assets.open("testphotos/$n").use { BitmapFactory.decodeStream(it) }
                val p = if (e == null) null else withContext(Dispatchers.Default) { runCatching { e.see(bmp) }.getOrNull() }
                bmp.recycle()
                if (p == null) { summary.appendLine("$n: eye not ready"); continue }
                var line = "-"
                var turnLog: JSONObject? = null
                if (p.verdict == Verdict.NORMAL || p.verdict == Verdict.PERSON) {
                    val person = p.verdict == Verdict.PERSON
                    val r = Mind.react(scratch, Seeing(p.verdict, p.label, p.cat, p.confidence, 1.0), System.currentTimeMillis())
                    val ev = if (person) Event.SawPerson(true) else Event.Saw(p.label, r.firstTime, r.count)
                    val c = withContext(textThread) { compose(scratch.traits, r.mood, ev, Mind.intentsFor(scratch, true)) }
                    line = c.line; turnLog = c.log
                }
                Telemetry.log("selftest", JSONObject().put("photo", n).put("perception", p.toLog())
                    .put("turn", turnLog ?: JSONObject.NULL))
                summary.appendLine("$n: ${p.verdict.name.lowercase()} ${p.label} (${p.msModel} ms) -> $line")
            }
            Telemetry.flushSoon()
            done(summary.toString())
        }
    }
}
