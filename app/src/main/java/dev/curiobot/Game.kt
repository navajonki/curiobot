package dev.curiobot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.curiobot.brain.CreatureState
import dev.curiobot.brain.Event
import dev.curiobot.brain.MYSTERY
import dev.curiobot.brain.Hunts
import dev.curiobot.brain.Ideas
import dev.curiobot.brain.Intent
import dev.curiobot.brain.Lines
import dev.curiobot.brain.Mind
import dev.curiobot.brain.Mood
import dev.curiobot.brain.Nest
import dev.curiobot.brain.Nicknames
import dev.curiobot.brain.Pace
import dev.curiobot.brain.Prompt
import dev.curiobot.brain.Reaction
import dev.curiobot.brain.Safety
import dev.curiobot.brain.Seeing
import dev.curiobot.brain.Thing
import dev.curiobot.brain.ThingMemory
import dev.curiobot.brain.Verdict
import dev.curiobot.brain.allOk
import dev.curiobot.brain.failures
import dev.curiobot.log.Prefs
import dev.curiobot.log.Telemetry
import dev.curiobot.text.ModelStore
import dev.curiobot.text.TextModel
import dev.curiobot.ui.Lcd
import dev.curiobot.ui.NestView
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

    /** Tamagotchi-style: next cycles the options, ok picks one, back leaves the current menu. */
    enum class Button { NEXT, OK, BACK }

    /** Where the options came from, which decides what Back does and the header. */
    private enum class Level { HOME, PHOTO, TEACH, DOZE, NEST, IDEA }

    /** What was just seen, kept so the person can correct it. */
    private data class SeenContext(
        val label: String?,
        val cat: String?,
        val thingId: Int?,
        val at: Long,
        val embedding: FloatArray,
        val novelty: Double,
        /** Other plausible words, best first: (word, category). */
        val alternatives: List<Pair<String, String>>,
    )

    var host: Host? = null

    @Volatile var ui = UiState(); private set
    /** Latest dithered camera frame, written by the camera thread. */
    @Volatile var cameraFrame: BooleanArray? = null; private set
    @Volatile var lastTurn: JSONObject? = null; private set

    private val scope: CoroutineScope = MainScope()
    private val textThread = Executors.newSingleThreadExecutor { Thread(it, "text-model") }.asCoroutineDispatcher()
    private val dir = File(ctx.filesDir, "creature").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val things = ThingMemory(File(dir, "things.bin"))
    private val rng = Random(System.nanoTime())

    private lateinit var state: CreatureState
    private var eye: Eye? = null
    private var text: TextModel? = null
    private var pace = Pace(Prefs.testPace)
    private var intents: List<Intent> = emptyList()
    private var level = Level.HOME
    /** "What do you think?" answers for the current photo, so it doesn't repeat itself. */
    private val thoughtsUsed = mutableListOf<String>()
    /** Per-option payloads (the word on a teaching option). */
    private var args: List<String?> = emptyList()
    private var mood = Mood.CONTENT
    private var newborn = false
    private var lastSeen: SeenContext? = null
    /** The idea on screen waiting for "That's cool!" or "Not too sure", and the words it came out as. */
    private var pendingIdea: Ideas.Plan? = null
    private var pendingIdeaText: String? = null
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
        File(dir, "sightings.bin").delete() // 0.1.x memory format, replaced by things.bin
        things.load()
        scope.launch {
            val t = System.currentTimeMillis()
            eye = withContext(Dispatchers.Default) {
                runCatching { Eye(ctx) }.onFailure { Telemetry.error("eye_init_failed", it) }.getOrNull()
            }
            eye?.let { Safety.useVocabulary(it.words) }
            Telemetry.log("eye_init", JSONObject().put("ok", eye != null).put("ms", System.currentTimeMillis() - t)
                .put("vocabulary", eye?.vocabularySize ?: 0).put("things", things.size))
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
                choose(listOf(Intent.TRY_AGAIN))
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
        val newWish = Mind.ensureWish(state, now, pace, rng.nextInt(1_000_000))
        newWish?.let { Telemetry.log("wish_new", JSONObject().put("wish", it.id).put("label", it.label)) }
        when {
            newborn -> {
                newborn = false
                mood = Mood.CURIOUS
                choose(Mind.intentsFor(state, fresh = false))
                Telemetry.log("hatched", traitsJson())
                showIdle(Lines.pick(Lines.HATCH, rng.nextInt(), name = state.traits.name), labelsFor(intents))
            }
            woke -> {
                Telemetry.log("wake", state.summary())
                mood = Mind.idleMood(state)
                turn(Event.Woke, fresh = false)
            }
            else -> {
                mood = Mind.idleMood(state)
                choose(Mind.intentsFor(state, fresh = false))
                val line = when {
                    state.asleep -> Lines.pick(Lines.LET_SLEEP, rng.nextInt())
                    newWish != null -> Lines.pick(Lines.WISH_NEW, rng.nextInt(), label = newWish.label)
                    else -> idleLine()
                }
                showIdle(line, labelsFor(intents))
            }
        }
        saveState()
        resume()
    }

    // ---------- input ----------

    fun press(b: Button) {
        host?.haptic()
        Telemetry.log("button", JSONObject().put("button", b.name.lowercase()).put("phase", ui.phase.name.lowercase())
            .put("cursor", ui.cursor).put("options", JSONArray(ui.options)))
        when (ui.phase) {
            Phase.IDLE, Phase.ERROR -> when (b) {
                Button.NEXT -> moveCursor(1)
                Button.OK -> intents.getOrNull(ui.cursor)?.let { perform(it, args.getOrNull(ui.cursor)) }
                Button.BACK -> goBack()
            }
            Phase.CAMERA -> when (b) {
                Button.BACK -> closeCamera(log = "cancelled").also { backToIdle() }
                Button.OK -> snap()
                Button.NEXT -> {}
            }
            else -> {}
        }
    }

    /** Back leaves whatever menu it's in. Home is the top, so there it does nothing. */
    private fun goBack() {
        Telemetry.log("back", JSONObject().put("from", level.name.lowercase()))
        when (level) {
            Level.HOME -> {}
            Level.PHOTO, Level.DOZE, Level.NEST -> backToIdle()
            Level.IDEA -> {
                // Leaving without deciding: the idea just isn't kept. It doesn't count against that kind of idea.
                pendingIdea?.let { Telemetry.log("idea_skipped", JSONObject().put("move", it.move.id)) }
                pendingIdea = null; pendingIdeaText = null
                mood = Mind.idleMood(state)
                choose(Mind.intentsFor(state, fresh = false))
                showIdle(Lines.pick(Ideas.SKIP, rng.nextInt()), labelsFor(intents))
            }
            Level.TEACH -> {
                val word = lastSeen?.label
                if (word == null) {
                    mood = Mind.idleMood(state)
                    choose(Mind.intentsFor(state, fresh = false))
                    showIdle(Lines.pick(Lines.TEACH_CANCEL_UNSURE, rng.nextInt()), labelsFor(intents))
                } else {
                    showPhotoMenu(Lines.pick(Lines.TEACH_CANCEL, rng.nextInt(), word))
                }
            }
        }
    }

    private fun showPhotoMenu(line: String) {
        choose(Mind.intentsFor(state, fresh = true, canAsk = canAsk()), level = Level.PHOTO)
        showIdle(line, labelsFor(intents))
    }

    private fun canAsk() = thoughtsUsed.size < 2

    private fun moveCursor(d: Int) {
        val n = ui.options.size
        if (n == 0) return
        show(ui.copy(cursor = Math.floorMod(ui.cursor + d, n)))
    }

    private fun perform(intent: Intent, arg: String?) {
        tickNeeds()
        Telemetry.log("intent", JSONObject().put("intent", intent.id).put("arg", arg ?: JSONObject.NULL)
            .put("creature", state.summary()))
        val now = System.currentTimeMillis()
        when (intent) {
            Intent.SHOW_NEW -> openCamera()
            Intent.ASK_ABOUT -> state.focusWord?.let { word ->
                Mind.spend(state, intent)
                val pool = (Lines.THOUGHTS[state.focusCat] ?: Lines.THOUGHTS_ANY).filter { it !in thoughtsUsed }
                    .ifEmpty { Lines.THOUGHTS_ANY }
                val thought = pool[rng.nextInt(pool.size)]
                thoughtsUsed += thought
                turn(Event.About(word, thought), fresh = true)
            } ?: backToIdle()
            Intent.SAY_HI -> { Mind.spend(state, intent); mood = Mood.FRIENDLY; turn(Event.Hello, fresh = false) }
            Intent.NEST -> showNest()
            Intent.REMEMBER -> {
                val e = Mind.pickMemory(state, rng.nextInt(1000))
                if (e == null) backToIdle() else { Mind.spend(state, intent); mood = Mood.CONTENT; turn(Event.Recall(e), fresh = false) }
            }
            Intent.MENU -> backToIdle()
            Intent.CORRECT -> if (lastSeen?.label == null) backToIdle()
                else { mood = Mood.PUZZLED; showTeachPage(0, Lines.pick(Lines.TEACH_ASK, rng.nextInt())) }
            Intent.TEACH_PICK -> {
                val word = arg ?: return backToIdle()
                val cat = lastSeen?.alternatives?.firstOrNull { it.first == word }?.second ?: ""
                applyTeach(word, cat)
            }
            Intent.TEACH_MORE -> showTeachPage(1, Lines.pick(Lines.TEACH_MORE, rng.nextInt()))
            Intent.TEACH_NONE -> applyTeach(MYSTERY, "mystery")
            Intent.HUNT, Intent.HUNT_SKIP -> {
                val h = Hunts.pick(state, rng.nextInt(1_000_000))
                Mind.startHunt(state, h, now)
                mood = Mood.CURIOUS
                choose(Mind.intentsFor(state, fresh = false))
                Telemetry.log("hunt_start", JSONObject().put("hunt", h.id).put("label", h.label)
                    .put("skipped_previous", intent == Intent.HUNT_SKIP))
                showIdle(Lines.pick(if (intent == Intent.HUNT) Lines.HUNT_START else Lines.HUNT_SKIP, rng.nextInt(),
                    label = h.label), labelsFor(intents))
                saveState()
            }
            Intent.WISH -> {
                val w = Hunts.byId(state.wishId)
                choose(Mind.intentsFor(state, fresh = false))
                showIdle(if (w == null || state.wishGranted) Lines.pick(Lines.WISH_DONE, 0)
                    else Lines.pick(Lines.WISH_SHOW, rng.nextInt(), label = w.label), labelsFor(intents))
            }
            Intent.NAP -> {
                Mind.startNap(state, now, pace)
                mood = Mood.SLEEPY
                choose(Mind.intentsFor(state, fresh = false))
                Telemetry.log("nap_start", JSONObject().put("minutes", pace.napMs / 60_000))
                showIdle(Lines.pick(Lines.NAP, rng.nextInt()), labelsFor(intents))
                saveState()
            }
            Intent.LET_SLEEP -> showIdle(Lines.pick(Lines.LET_SLEEP, rng.nextInt()), labelsFor(intents))
            Intent.PEEK -> {
                state.dreamShown = true
                val nick = things.things.filter { it.nickname != null }.maxByOrNull { it.lastSeen }?.nickname
                val word = nick ?: state.seen.keys.lastOrNull { it != "person" }
                choose(Mind.intentsFor(state, fresh = false))
                showIdle(if (word == null) Lines.pick(Lines.DREAM_EMPTY, 0) else Lines.pick(Lines.DREAM, rng.nextInt(), word),
                    labelsFor(intents))
            }
            Intent.WAKE -> {
                val progress = Mind.wakeEarly(state, now, pace)
                mood = Mood.SLEEPY
                choose(Mind.intentsFor(state, fresh = false))
                Telemetry.log("wake_early", JSONObject().put("nap_progress", Math.round(progress * 100) / 100.0)
                    .put("energy", Math.round(state.energy * 100) / 100.0))
                showIdle(Lines.pick(Lines.WOKE_EARLY, rng.nextInt()), labelsFor(intents))
                saveState()
            }
            Intent.TRY_AGAIN -> scope.launch { prepareTextModel() }
            Intent.REFLECT -> {
                val plan = Ideas.plan(state, nicknameMap(), rng)
                if (plan == null) {
                    choose(Mind.intentsFor(state, fresh = false))
                    showIdle(Lines.pick(Ideas.NEED_MORE, rng.nextInt()), labelsFor(intents))
                } else {
                    Mind.spend(state, intent)
                    state.ideasHad++
                    pendingIdea = plan; pendingIdeaText = null
                    mood = Mood.CURIOUS
                    Telemetry.log("idea_plan", JSONObject().put("move", plan.move.id)
                        .put("things", JSONArray(plan.things.map { it.name })).put("kinds", plan.kinds ?: JSONObject.NULL)
                        .put("builds_on", plan.idea?.text ?: JSONObject.NULL)
                        .put("move_scores", JSONObject(state.moveScores as Map<*, *>)))
                    turn(Event.Thought(plan), fresh = false, menu = listOf(Intent.KEEP_IDEA, Intent.DROP_IDEA) to Level.IDEA)
                }
            }
            Intent.KEEP_IDEA, Intent.DROP_IDEA -> {
                val plan = pendingIdea
                val text = pendingIdeaText
                pendingIdea = null; pendingIdeaText = null
                if (plan != null && text != null) {
                    val keep = intent == Intent.KEEP_IDEA
                    if (keep) Ideas.keep(state, plan, text, now) else Ideas.drop(state, plan)
                    Telemetry.log(if (keep) "idea_kept" else "idea_dropped", JSONObject().put("move", plan.move.id)
                        .put("text", text).put("ideas_kept", state.ideas.size)
                        .put("move_scores", JSONObject(state.moveScores as Map<*, *>)))
                    mood = if (keep) Mood.DELIGHTED else Mood.CONTENT
                    choose(Mind.intentsFor(state, fresh = false))
                    showIdle(Lines.pick(if (keep) Ideas.KEEP else Ideas.DROP, rng.nextInt()), labelsFor(intents))
                    saveState()
                } else backToIdle()
            }
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
                Telemetry.log("camera_open", JSONObject().put("hunt", state.huntId ?: JSONObject.NULL))
            } else {
                Telemetry.warn("camera_denied", JSONObject())
                choose(Mind.intentsFor(state, fresh = false))
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
        val now = System.currentTimeMillis()
        when (p.verdict) {
            Verdict.TRIPWIRE -> {
                // Look away; nothing is remembered, named, or described. Teaching can't override this.
                Telemetry.log("tripwire", JSONObject().put("group", p.tripGroup).put("score", p.tripScore))
                mood = Mood.QUIET
                lastSeen = null
                choose(Mind.intentsFor(state, fresh = false))
                showIdle(Lines.pick(Lines.LOOK_AWAY, rng.nextInt()), labelsFor(intents))
            }
            Verdict.LOWINFO -> {
                mood = Mood.PUZZLED
                lastSeen = null
                choose(Mind.intentsFor(state, fresh = false))
                showIdle(Lines.pick(Lines.LOWINFO[p.label] ?: Lines.LOWINFO.getValue("blank"), rng.nextInt()), labelsFor(intents))
            }
            Verdict.PERSON -> {
                // Photos of people are never kept, not even as embeddings, and never matched to things.
                lastSeen = null
                val r = Mind.react(state, Seeing(Verdict.PERSON, "person", "person", p.confidence, 0.5), now)
                mood = r.mood
                logReaction("person", r, 0.5)
                turn(Event.SawPerson(r.firstTime), fresh = true)
            }
            Verdict.NORMAL, Verdict.UNSURE -> {
                val rec = things.recognize(p.embedding)
                val novelty = things.novelty(p.embedding)
                rec?.let {
                    Telemetry.log("recognized", JSONObject().put("thing", it.thing.id).put("word", it.thing.word)
                        .put("nickname", it.thing.nickname ?: JSONObject.NULL).put("taught", it.thing.taught)
                        .put("similarity", r3(it.similarity)).put("clip_label", p.label)
                        .put("clip_verdict", p.verdict.name.lowercase()))
                }
                if (rec == null && p.verdict == Verdict.UNSURE) {
                    // It doesn't know: ask the person, which is how it learns.
                    lastSeen = SeenContext(null, null, null, now, p.embedding, novelty, p.alternatives)
                    mood = Mood.PUZZLED
                    showTeachPage(0, Lines.pick(Lines.UNSURE_ASK, rng.nextInt()))
                    return
                }
                val word = rec?.thing?.word ?: p.label
                val cat = rec?.thing?.cat ?: p.cat
                val nestBefore = Nest.before(state)
                val r = Mind.react(state, Seeing(Verdict.NORMAL, word, cat, p.confidence, novelty), now)
                thoughtsUsed.clear()
                val thing = rec?.thing?.also { things.observe(it, p.embedding, now) } ?: things.add(word, cat, p.embedding, now)
                lastSeen = SeenContext(word, cat, thing.id, now, p.embedding, novelty, p.alternatives.filter { it.first != word })
                mood = r.mood
                logReaction(word, r, novelty)
                val ev = specialEvent(word, cat, thing, now)
                    ?: nestEvent(nestBefore, word, cat, r.firstTime)
                    ?: Event.Saw(word, r.firstTime, r.count, thing.nickname)
                things.save()
                turn(ev, fresh = true)
            }
        }
    }

    /** A hunt found, a wish granted or a new nickname beats an ordinary sighting. */
    private fun specialEvent(word: String, cat: String, thing: Thing, now: Long): Event? {
        Hunts.byId(state.huntId)?.takeIf { it.matches(word, cat) }?.let { h ->
            val e = Mind.completeHunt(state, h, word, now)
            mood = Mood.DELIGHTED
            Telemetry.log("hunt_found", JSONObject().put("hunt", h.id).put("word", word).put("journal", e.text)
                .put("hunts_done", state.huntsDone))
            return Event.Found(word, h.label)
        }
        if (!state.wishGranted) Hunts.byId(state.wishId)?.takeIf { it.matches(word, cat) }?.let { w ->
            val e = Mind.grantWish(state, w, word, now)
            mood = Mood.DELIGHTED
            Telemetry.log("wish_granted", JSONObject().put("wish", w.id).put("word", word).put("journal", e.text))
            return Event.WishGranted(word, w.label)
        }
        if (thing.nickname == null && thing.count >= ThingMemory.NAME_AFTER) {
            val nick = Nicknames.make(word, state.seed, thing.id)
            thing.nickname = nick
            Mind.named(state, word, nick, now)
            mood = Mood.DELIGHTED
            Telemetry.log("named", JSONObject().put("thing", thing.id).put("word", word).put("nickname", nick)
                .put("count", thing.count))
            return Event.Named(word, nick)
        }
        state.huntId?.let { Telemetry.log("hunt_miss", JSONObject().put("hunt", it).put("word", word)) }
        return null
    }

    /** A new kind for the nest, a new favorite, growth, or a link to something seen before. */
    private fun nestEvent(before: Nest.Before, word: String, cat: String, firstTime: Boolean): Event? {
        val e = Nest.change(state, before, word, cat, firstTime) ?: return null
        val now = System.currentTimeMillis()
        val day = Mind.dayNumber(state, now)
        val out = if (e is Event.GoesWith) e.copy(other = nicknameFor(e.other)) else e
        when (out) {
            is Event.NewKind -> state.addJournal(dev.curiobot.brain.JournalEntry(now, word, mood.word,
                "Day $day: started a spot in my nest for ${out.kinds}."))
            is Event.Favorite -> state.addJournal(dev.curiobot.brain.JournalEntry(now, word, mood.word,
                "Day $day: decided ${out.kinds} are my favorite."))
            else -> {}
        }
        Telemetry.log("nest", JSONObject().put("event", out.javaClass.simpleName).put("word", word).put("cat", cat)
            .put("line", out.promptLine()).put("kinds_found", Nest.kindsFound(state)).put("things_found", Nest.thingsFound(state)))
        return out
    }

    /** Word to nickname for every thing it has named. */
    private fun nicknameMap(): Map<String, String> = things.things.filter { it.nickname != null }
        .sortedBy { it.lastSeen }.associate { it.word to it.nickname!! }

    /** A thing's nickname if it has one ("Mugsby"), else its word. */
    private fun nicknameFor(word: String) = things.things.filter { it.word == word && it.nickname != null }
        .maxByOrNull { it.lastSeen }?.nickname ?: word

    private fun showNest() {
        val slots = Nest.slots(state)
        val found = slots.count { it.words.isNotEmpty() }
        val fav = Nest.favorite(state)?.let { Nest.NAMES[it] }
        val newest = state.seen.entries.filter { it.value.cat in Nest.KINDS }.maxByOrNull { it.value.last }?.key
        val detail = listOfNotNull(fav?.let { "Favorite: $it." }, newest?.let { "Newest: $it." },
            state.ideas.size.takeIf { it > 0 }?.let { "Ideas kept: $it." })
            .joinToString(" ").ifEmpty { "Show me things to fill it up." }
        choose(emptyList(), level = Level.NEST)
        Telemetry.log("nest_view", JSONObject().put("kinds_found", found).put("things_found", Nest.thingsFound(state)))
        ui = ui.copy(phase = Phase.IDLE, options = emptyList(), cursor = 0, title = "my nest",
            nest = NestView(slots.associate { it.kind to it.level }, "My nest: $found of ${slots.size} kinds", detail))
    }

    private fun logReaction(word: String, r: Reaction, novelty: Double) {
        Telemetry.log("reaction", JSONObject().apply {
            put("word", word)
            put("mood", r.mood.word); put("first_time", r.firstTime); put("count", r.count)
            put("score", r2(r.score)); put("image_novelty", r2(novelty))
            put("satisfaction_delta", r2(r.satisfactionDelta)); put("energy_delta", r2(r.energyDelta))
            put("journal", r.journal?.text ?: JSONObject.NULL)
        })
    }

    // ---------- teaching ----------

    /** Offer its next guesses, three at a time, then "None of these". */
    private fun showTeachPage(page: Int, line: String) {
        val ls = lastSeen ?: return backToIdle()
        val slice = ls.alternatives.drop(page * 3).take(3)
        val more = page == 0 && ls.alternatives.size > 3
        choose(List(slice.size) { Intent.TEACH_PICK } + (if (more) Intent.TEACH_MORE else Intent.TEACH_NONE),
            slice.map { it.first } + null, Level.TEACH)
        Telemetry.log("teach_offer", JSONObject().put("page", page).put("guesses", JSONArray(slice.map { it.first }))
            .put("current", ls.label ?: JSONObject.NULL))
        showIdle(line, labelsFor(intents))
    }

    /** The person said what it really is: fix the memory and remember the photo by that word. */
    private fun applyTeach(word: String, cat: String) {
        val ls = lastSeen ?: return backToIdle()
        val now = System.currentTimeMillis()
        val firstLook = ls.label == null
        ls.label?.let { Mind.unrecord(state, it, ls.at) }
        val thing = ls.thingId?.let { things.byId(it) }?.also { things.relabel(it, word, cat) }
            ?: things.add(word, cat, ls.embedding, now, taught = true)
        val r = Mind.react(state, Seeing(Verdict.NORMAL, word, cat, 1.0, ls.novelty), now, applyNeeds = firstLook)
        state.taughtCount++
        Telemetry.log("taught", JSONObject().put("from", ls.label ?: JSONObject.NULL).put("to", word)
            .put("guess_rank", ls.alternatives.indexOfFirst { it.first == word }).put("thing", thing.id)
            .put("path", if (firstLook) "unsure" else "correction").put("taught_total", state.taughtCount))
        lastSeen = ls.copy(label = word, cat = cat, thingId = thing.id, at = now,
            alternatives = ls.alternatives.filter { it.first != word })
        mood = if (r.mood == Mood.WARY) Mood.WARY else Mood.CURIOUS
        thoughtsUsed.clear()
        // The right word can finish a hunt or grant the wish, same as a photo would.
        val special = if (word == MYSTERY) null else specialEvent(word, cat, thing, now)
        things.save()
        turn(special ?: Event.Taught(word), fresh = true)
    }

    // ---------- turns ----------

    /** Ask the text model for a line + labels, check them, fall back if needed. */
    /** [menu] overrides the options that follow (an idea's keep/drop choice). */
    private fun turn(event: Event, fresh: Boolean, menu: Pair<List<Intent>, Level>? = null) {
        val grewNest = event is Event.NewKind || event is Event.KindGrew || event is Event.Favorite
        val next = menu?.first ?: afterTurnIntents(fresh).let { if (grewNest && it != DOZE_CUE) it + Intent.NEST else it }
        choose(next, level = when {
            menu != null -> menu.second
            next == DOZE_CUE -> Level.DOZE
            fresh -> Level.PHOTO
            else -> Level.HOME
        })
        show(ui.copy(phase = Phase.THINKING, frozen = null, line = ""))
        val traits = state.traits
        val m = mood
        scope.launch {
            val result = withContext(textThread) { compose(traits, m, event, next) }
            lastTurn = result.log
            if (event is Event.Thought) pendingIdeaText = result.line
            Telemetry.log("turn", result.log)
            showIdle(result.line, result.labels)
            saveState()
        }
    }

    private fun afterTurnIntents(fresh: Boolean): List<Intent> =
        if (Mind.shouldDoze(state)) DOZE_CUE.also { Telemetry.log("dozing_cue", state.summary()) }
        else Mind.intentsFor(state, fresh, canAsk())

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
        if (tm != null && !event.written) {
            for (attempt in 1..2) {
                val temp = if (attempt == 1) 0.8 else 0.6
                val seed = rng.nextInt(1, Int.MAX_VALUE)
                val out = runCatching { tm.write(spec, temp, seed) }
                    .onFailure { Telemetry.error("text_model_failed", it) }.getOrNull() ?: break
                val parsed = Prompt.parse(out.raw, modelIntents.size)
                val checks = parsed.line?.let { Safety.checkLine(it, spec.requiredWord, spec.aboutPerson, spec.context, spec.selfName, spec.requiredWords) }
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
        if (event.written) lineSource = "written"
        val finalLine = line ?: fallbackLine(m, event, traits.name)
        // Labels: the model's if they pass, else the written one for that intent.
        val labelLog = JSONArray()
        var mi = 0
        val labels = next.map { intent ->
            val written = intent.fallbackLabel(state.focusWord, rng.nextInt(10))
            if (!intent.modelLabel) {
                labelLog.put(JSONObject().put("intent", intent.id).put("label", written).put("source", "fixed"))
                return@map written
            }
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
            put("required_words", JSONArray(spec.requiredWords))
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
        val roll = rng.nextInt()
        return when (event) {
            is Event.SawPerson -> Lines.pick(Lines.SAW_PERSON, roll)
            is Event.Hello -> Lines.pick(Lines.HELLO, roll)
            is Event.Woke -> Lines.pick(Lines.WOKE, roll)
            is Event.Taught -> if (event.unnamed) Lines.pick(Lines.UNNAMED, roll) else Lines.pick(Lines.TAUGHT, roll, event.word)
            is Event.About -> Lines.pick(Lines.ABOUT, roll, event.word, label = event.thought)
            is Event.Found -> Lines.pick(Lines.FOUND, roll, event.word)
            is Event.WishGranted -> Lines.pick(Lines.WISH_CAME_TRUE, roll, event.word)
            is Event.Named -> Lines.pick(Lines.NAMED, roll, event.word, label = event.nickname)
            is Event.NewKind -> Lines.pick(Lines.NEW_KIND, roll, event.word, label = event.kinds)
            is Event.Favorite -> Lines.pick(Lines.FAVORITE, roll, event.word, label = event.kinds)
            is Event.KindGrew -> Lines.pick(Lines.KIND_GREW, roll, event.word, label = event.kinds)
            is Event.GoesWith -> Lines.pick(Lines.GOES_WITH, roll, event.word, label = event.other)
            is Event.Thought -> Ideas.fallback(event.plan, roll)
            else -> event.requiredWord?.let { Lines.pick(Lines.FALLBACK[m] ?: Lines.FALLBACK.getValue(Mood.CURIOUS), roll, it, name) }
                ?: Lines.pick(Lines.IDLE[m] ?: Lines.IDLE.getValue(Mood.CURIOUS), roll)
        }
    }

    private fun choose(list: List<Intent>, a: List<String?> = emptyList(), level: Level = Level.HOME) {
        intents = list
        args = a
        this.level = level
    }

    /** The header: what this menu is about. Home has none. */
    private fun titleFor(): String? = when (level) {
        Level.HOME -> null
        Level.PHOTO -> if (state.focusKind == "person") "hello" else (lastSeen?.label ?: state.focusWord)?.let { nicknameFor(it) }
        Level.TEACH -> "what is it?"
        Level.DOZE -> "sleepy"
        Level.NEST -> "my nest"
        Level.IDEA -> "new idea"
    }

    private fun labelsFor(list: List<Intent>) = list.mapIndexed { i, it -> it.fallbackLabel(args.getOrNull(i) ?: state.focusWord, 0) }

    // ---------- needs & display ----------

    private fun tickNeeds() {
        if (!::state.isInitialized) return
        val now = System.currentTimeMillis()
        val woke = Mind.tick(state, now, pace)
        Mind.ensureWish(state, now, pace, rng.nextInt(1_000_000))?.let {
            Telemetry.log("wish_new", JSONObject().put("wish", it.id).put("label", it.label))
        }
        if (woke && ui.phase == Phase.IDLE) {
            Telemetry.log("wake", state.summary())
            mood = Mind.idleMood(state)
            turn(Event.Woke, fresh = false)
            return
        }
        if (ui.phase == Phase.IDLE) show(ui.copy(satisfaction = state.satisfaction, energy = state.energy,
            asleep = state.asleep, wish = wishActive()))
    }

    private fun idleLine(): String {
        Hunts.byId(state.huntId)?.let { h -> if (rng.nextInt(2) == 0) return Lines.pick(Lines.HUNT_REMINDER, rng.nextInt(), label = h.label) }
        return Lines.pick(Lines.IDLE[mood] ?: Lines.IDLE.getValue(Mood.CURIOUS), rng.nextInt())
    }

    private fun backToIdle() {
        mood = Mind.idleMood(state)
        choose(Mind.intentsFor(state, fresh = false))
        showIdle(idleLine(), labelsFor(intents))
    }

    private fun wishActive() = state.wishId != null && !state.wishGranted

    private fun showIdle(line: String, labels: List<String>) {
        show(UiState(
            phase = Phase.IDLE, name = state.traits.name, satisfaction = state.satisfaction, energy = state.energy,
            mood = if (state.asleep) Mood.SLEEPY else mood, line = line, options = labels, cursor = 0,
            spriteSeed = state.seed, asleep = state.asleep, wish = wishActive(), title = titleFor(),
        ))
        Telemetry.log("screen", JSONObject().put("line", line).put("options", JSONArray(labels))
            .put("menu", level.name.lowercase())
            .put("mood", mood.word).put("asleep", state.asleep))
    }

    private fun show(s: UiState) {
        ui = if (::state.isInitialized && s.name.isEmpty()) s.copy(name = state.traits.name, spriteSeed = state.seed,
            satisfaction = state.satisfaction, energy = state.energy, asleep = state.asleep, wish = wishActive()) else s
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
            put("curiosity", r2(t.curiosity)); put("shyness", r2(t.shyness))
        }
    }

    // ---------- dev panel ----------

    fun statusText(): String = buildString {
        appendLine("device   ${Telemetry.deviceId.take(8)}  session ${Telemetry.sessionId}")
        appendLine("build    ${Telemetry.build}")
        appendLine("text     ${textBackend}  model file ${if (ModelStore.isReady(ctx)) "ready" else "missing"}")
        appendLine("eye      ${if (eyeReady) "ready" else "not ready"}  things ${things.size}" +
            " (named ${things.things.count { it.nickname != null }}, taught ${things.things.count { it.taught }})")
        appendLine("logs     upload ${if (Telemetry.uploadEnabled) "on" else "off"}, pending ${Telemetry.pendingCount()}, sent ${Telemetry.uploadedTotal}" +
            (Telemetry.lastUploadError?.let { "\n         last error: $it" } ?: ""))
        appendLine("pace     ${if (pace.test) "test (hours are minutes, a day is 24 min)" else "real"}")
        if (::state.isInitialized) {
            val t = state.traits
            appendLine("ideas    kept ${state.ideas.size} of ${state.ideasHad} had")
            appendLine("nest     ${Nest.kindsFound(state)} of ${Nest.KINDS.size} kinds, ${Nest.thingsFound(state)} things" +
                (Nest.favorite(state)?.let { ", favorite ${Nest.NAMES[it]}" } ?: ""))
            appendLine("creature ${t.name} (${t.temperament}, ${t.style})")
            appendLine("         loves ${t.loves.joinToString()}, dislikes ${t.dislikes}")
            appendLine("         curiosity %.2f  energy %.2f  words %d  asleep %s".format(state.satisfaction, state.energy, state.seen.size, state.asleep))
            appendLine("         hunt ${Hunts.byId(state.huntId)?.label ?: "none"} (done ${state.huntsDone})  " +
                "wish ${Hunts.byId(state.wishId)?.label ?: "none"}${if (state.wishGranted) " (granted)" else ""}")
        }
    }

    /** Everything the creature keeps on disk, made readable, for the dev panel. */
    fun filesText(): String = buildString {
        if (!::state.isInitialized) return "Not loaded yet."
        val fmt = java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.US)
        appendLine("FILES (app storage/creature)")
        dir.listFiles()?.sortedBy { it.name }?.forEach { appendLine("  ${it.name.padEnd(18)} ${"%,d".format(it.length())} bytes") }
        appendLine()
        appendLine("JOURNAL, newest first (${state.journal.size} of ${CreatureState.MAX_JOURNAL} kept)")
        state.journal.asReversed().forEach { appendLine("  ${fmt.format(it.ts)}  ${it.text}") }
        if (state.journal.isEmpty()) appendLine("  (empty)")
        appendLine()
        appendLine("NEST")
        for (slot in Nest.slots(state)) {
            val name = Nest.NAMES[slot.kind] ?: slot.kind
            appendLine("  ${name.padEnd(16)} ${"*".repeat(slot.level).padEnd(3, '.')} ${slot.words.joinToString(", ").ifEmpty { "-" }}")
        }
        appendLine()
        appendLine("WORDS SEEN (${state.seen.size})")
        state.seen.entries.sortedByDescending { it.value.last }.forEach { (w, sg) ->
            appendLine("  ${w.padEnd(16)} x${sg.count}  ${sg.cat.padEnd(9)} last ${fmt.format(sg.last)}, ${sg.lastMood}")
        }
        appendLine()
        appendLine("THINGS IT RECOGNIZES (things.bin: ${things.size}, image fingerprints only, never photos)")
        things.things.sortedByDescending { it.lastSeen }.forEach { t ->
            appendLine("  #${t.id} ${t.word}${t.nickname?.let { " \"$it\"" } ?: ""}${if (t.taught) " (taught)" else ""}" +
                "  seen ${t.count}, ${t.embeddings.size} angle(s), last ${fmt.format(t.lastSeen)}")
        }
        appendLine()
        appendLine("IDEAS KEPT (${state.ideas.size}; had ${state.ideasHad}, kept ${state.ideasKept})")
        state.ideas.asReversed().forEach { appendLine("  ${fmt.format(it.ts)}  [${it.move}] ${it.text}") }
        if (state.ideas.isEmpty()) appendLine("  (none yet)")
        appendLine("  what it leans toward: " + Ideas.Move.values().joinToString(", ") {
            "${it.id} ${"%.1f".format(state.moveScores[it.id] ?: Ideas.SCORE_START)}" })
        appendLine()
        appendLine("HUNT ${Hunts.byId(state.huntId)?.label ?: "none"}   done ${state.huntsDone}   recent ${state.recentHunts.joinToString()}")
        appendLine("WISH ${Hunts.byId(state.wishId)?.label ?: "none"}${if (state.wishGranted) " (granted)" else ""}")
        appendLine()
        appendLine("STATE.JSON")
        append(runCatching { JSONObject(stateFile.readText()).toString(2) }.getOrElse { "(couldn't read: ${it.message})" })
    }

    fun resetCreature() {
        Telemetry.log("creature_reset", traitsJson())
        stateFile.delete()
        things.clear()
        lastSeen = null
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

    private companion object {
        val DOZE_CUE = listOf(Intent.NAP, Intent.MENU)
    }

    private fun r2(d: Double) = Math.round(d * 100) / 100.0
    private fun r3(d: Double) = Math.round(d * 1000) / 1000.0
}
