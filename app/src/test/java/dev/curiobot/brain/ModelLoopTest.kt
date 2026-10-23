package dev.curiobot.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Bridges the app's prompts and the real model, which can't run in a JVM test.
 *
 * 1. `exportFixtures` writes the exact prompts the app would send.
 * 2. tools/model/run_fixtures.py runs them through the shipped model file.
 * 3. `evaluateOutputs` parses and safety-checks what came back, like the app does.
 */
class ModelLoopTest {
    private val buildDir = File("build").apply { mkdirs() }

    @Test fun exportFixtures() {
        val out = JSONArray()
        val intentsFresh = listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW)
        val intentsAsked = listOf(Intent.CORRECT, Intent.SHOW_NEW)
        val intentsIdle = listOf(Intent.SHOW_NEW, Intent.HUNT, Intent.WISH, Intent.NEST, Intent.REMEMBER)
        // Person, hello and waking are written lines only, so they aren't here.
        val cases = listOf(
            Triple(Mood.DELIGHTED, Event.Saw("dog", true, 1), intentsFresh),
            Triple(Mood.CURIOUS, Event.Saw("bicycle", true, 1), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Saw("banana", true, 1), intentsFresh),
            Triple(Mood.BORED, Event.Saw("chair", false, 6), intentsFresh),
            Triple(Mood.CURIOUS, Event.Saw("pen", true, 1), intentsFresh),
            Triple(Mood.CURIOUS, Event.About("mug", "a slow afternoon"), intentsFresh),
            Triple(Mood.CURIOUS, Event.About("jacket", "a chilly walk"), intentsAsked),
            Triple(Mood.CONTENT, Event.Recall(JournalEntry(0, "tiger", "delighted", "Day 2: first tiger. Loved it.")), intentsIdle),
            Triple(Mood.CURIOUS, Event.Taught("cookie jar"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Found("pillow", "something soft"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Found("spoon", "a spoon"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.WishGranted("apple", "something to eat"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Named("mug", "Mugsby"), intentsFresh),
            Triple(Mood.CONTENT, Event.Saw("mug", false, 5, "Mugsby"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.NewKind("cup", "kitchen things"), intentsFresh),
            Triple(Mood.CURIOUS, Event.GoesWith("pen", "marker", "art things"), intentsFresh),
            Triple(Mood.CURIOUS, Event.GoesWith("fork", "Mugsby", "kitchen things"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.KindGrew("rabbit", "animals", 3), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Favorite("spoon", "kitchen things"), intentsFresh),
        ) + ideaCases() + moreCases() + storyCases()
        for (seed in listOf(11L, 222L, 3333L)) {
            val t = Genome.traitsFor(seed)
            for ((mood, ev, intents) in cases) {
                val spec = Prompt.build(t, mood, ev, intents)
                out.put(JSONObject().apply {
                    put("system", spec.system)
                    put("examples", JSONArray(spec.examples.map { JSONArray(listOf(it.first, it.second)) }))
                    put("user", spec.user)
                    put("required_word", spec.requiredWord ?: JSONObject.NULL)
                    put("required_words", JSONArray(spec.requiredWords))
                    put("long_line", spec.longLine)
                    put("about_person", spec.aboutPerson)
                    put("context", spec.context)
                    put("self_name", spec.selfName ?: JSONObject.NULL)
                    put("n", spec.intents.count { it.modelLabel })
                })
            }
        }
        File(buildDir, "prompt_fixtures.json").writeText(out.toString(2))
    }

    private fun ideaCases(): List<Triple<Mood, Event, List<Intent>>> {
        fun t(w: String, c: String, n: String? = null) = Ideas.Thing(w, c, n)
        val kept = Idea(0, "common", listOf("cup", "bicycle"), "I think the cup and the bicycle both go round and round.",
            "go round and round")
        val plans = listOf(
            Ideas.Plan(Ideas.Move.COMMON, listOf(t("cup", "kitchen"), t("bicycle", "vehicle")), hook = "go round and round"),
            Ideas.Plan(Ideas.Move.COMMON, listOf(t("mug", "kitchen", "Mugsby"), t("fern", "plant")), hook = "love sunlight"),
            Ideas.Plan(Ideas.Move.COMMON, listOf(t("chair", "furniture"), t("tree", "plant")), hook = "wait patiently"),
            Ideas.Plan(Ideas.Move.FRIENDS, listOf(t("pillow", "home"), t("cat", "animal")), hook = "like naps"),
            Ideas.Plan(Ideas.Move.FRIENDS, listOf(t("jacket", "clothing"), t("umbrella", "home")), hook = "like going outside"),
            Ideas.Plan(Ideas.Move.FRIENDS, listOf(t("mug", "kitchen", "Mugsby"), t("spoon", "kitchen")), hook = "help at breakfast"),
            Ideas.Plan(Ideas.Move.TEAM, listOf(t("spoon", "kitchen"), t("banana", "food")), hook = "make someone happy"),
            Ideas.Plan(Ideas.Move.TEAM, listOf(t("lamp", "home"), t("bicycle", "vehicle")), hook = "go places"),
            Ideas.Plan(Ideas.Move.KIND_RULE, listOf(t("cup", "kitchen"), t("fork", "kitchen")), kinds = "kitchen things", hook = "help at breakfast"),
            Ideas.Plan(Ideas.Move.KIND_RULE, listOf(t("dog", "animal"), t("cat", "animal")), kinds = "animals", hook = "like naps"),
            Ideas.Plan(Ideas.Move.FAVORITE_WHY, listOf(t("spoon", "kitchen")), kinds = "kitchen things", hook = "get used every day"),
            Ideas.Plan(Ideas.Move.BUILD_ON, listOf(t("pen", "art")), idea = kept, hook = kept.hook),
        )
        return plans.map { Triple(Mood.CURIOUS, Event.Thought(it) as Event, listOf(Intent.KEEP_IDEA, Intent.DROP_IDEA)) }
    }

    private fun moreCases(): List<Triple<Mood, Event, List<Intent>>> {
        fun t(w: String, c: String, n: String? = null) = Ideas.Thing(w, c, n)
        val menu = listOf(Intent.KEEP_IDEA, Intent.SAY_MORE, Intent.DROP_IDEA)
        val cupBike = Ideas.Plan(Ideas.Move.COMMON, listOf(t("cup", "kitchen"), t("bicycle", "vehicle")), hook = "go round and round")
        val mugFern = Ideas.Plan(Ideas.Move.COMMON, listOf(t("mug", "kitchen", "Mugsby"), t("fern", "plant")), hook = "love sunlight")
        val pillowCat = Ideas.Plan(Ideas.Move.FRIENDS, listOf(t("pillow", "home"), t("cat", "animal")), hook = "like naps")
        val jacketUmb = Ideas.Plan(Ideas.Move.FRIENDS, listOf(t("jacket", "clothing"), t("umbrella", "home")), hook = "like going outside")
        val spoonBan = Ideas.Plan(Ideas.Move.TEAM, listOf(t("spoon", "kitchen"), t("banana", "food")), hook = "make someone happy")
        val animals = Ideas.Plan(Ideas.Move.KIND_RULE, listOf(t("dog", "animal"), t("cat", "animal")), kinds = "animals", hook = "like naps")
        val fav = Ideas.Plan(Ideas.Move.FAVORITE_WHY, listOf(t("spoon", "kitchen")), kinds = "kitchen things", hook = "get used every day")
        val g = Ideas.Grow.COULD_EVEN; val st = Ideas.Grow.STORY; val om = Ideas.Grow.ONE_MORE
        val cases = listOf(
            Triple(cupBike, "I think the cup and the bicycle go round and round!", Ideas.More(g, "maybe the cup could even have wheels", "cup")),
            Triple(mugFern, "Mugsby and the fern both love the sun!", Ideas.More(g, "maybe Mugsby the mug could even grow a little every day", "Mugsby")),
            Triple(pillowCat, "I think the pillow and the cat like naps together!", Ideas.More(g, "maybe the pillow could even get curious", "pillow")),
            Triple(animals, "I think the dog and cat like naps!", Ideas.More(g, "maybe the dog could even make sounds", "dog")),
            Triple(cupBike, "I think the cup and the bicycle go round and round!", Ideas.More(st, "a tiny story: the cup and the bicycle go round and round together late at night", "cup")),
            Triple(jacketUmb, "I think the jacket and the umbrella both like going outside!", Ideas.More(st, "a tiny story: the jacket and the umbrella like going outside together on a rainy day", "jacket")),
            Triple(spoonBan, "The spoon and the banana, they both make someone happy!", Ideas.More(st, "a tiny story: the spoon and the banana make someone happy together at breakfast", "spoon")),
            Triple(fav, "I like the spoon, it's always there!", Ideas.More(st, "a tiny story: the spoon gets used every day on a sunny morning", "spoon")),
            Triple(cupBike, "I think the cup and the bicycle go round and round!", Ideas.More(om, "the pen joins in, because it goes round and round too", "pen", t("pen", "art"))),
            Triple(pillowCat, "I think the pillow and the cat like naps together!", Ideas.More(om, "Mugsby the mug joins in, because it likes naps too", "Mugsby", t("mug", "kitchen", "Mugsby"))),
            Triple(jacketUmb, "I think the jacket and the umbrella both like going outside!", Ideas.More(om, "the lamp joins in, because it likes going outside too", "lamp", t("lamp", "home"))),
            Triple(animals, "I think the dog and cat like naps!", Ideas.More(om, "the teddy bear joins in, because it likes naps too", "teddy bear", t("teddy bear", "toy"))),
        )
        return cases.map { (p, bud, m) -> Triple(Mood.DELIGHTED, Event.MoreThought(p, m, bud) as Event, menu) }
    }

    private fun storyCases(): List<Triple<Mood, Event, List<Intent>>> {
        val out = mutableListOf<Triple<Mood, Event, List<Intent>>>()
        val casts = listOf(
            listOf("mug" to "kitchen", "fern" to "plant", "sock" to "clothing") to mapOf("mug" to "Mugsby"),
            listOf("pillow" to "home", "banana" to "food", "lamp" to "home") to emptyMap(),
            listOf("teddy bear" to "toy", "spoon" to "kitchen", "book" to "home") to emptyMap(),
        )
        for ((i, c) in casts.withIndex()) {
            val s = CreatureState(7L + i, 0)
            c.first.forEachIndexed { k, (w, cat) -> Mind.react(s, Seeing(Verdict.NORMAL, w, cat, 0.9, 1.0), 1_000L + k) }
            val run = Stories.start(s, c.second, kotlin.random.Random(10 + i))!!
            repeat(4) { beat ->
                val choices = run.choices()
                out += Triple(Mood.DELIGHTED, Event.StoryBeat(run) as Event, choices.map { Intent.STORY_CHOICE }.ifEmpty { listOf(Intent.STORY_END) })
                if (beat < 3) run.choose(choices[(beat + i) % 2])
            }
        }
        return out
    }

    @Test fun evaluateOutputs() {
        val f = File(buildDir, "model_outputs.json")
        assumeTrue("run tools/model/run_fixtures.py first", f.exists())
        val items = JSONArray(f.readText())
        Safety.useVocabulary(JSONObject(File("src/main/assets/vocab.json").readText()).getJSONArray("rows")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.getString("kind") == "normal" }.map { it.getString("label") })
        var wellFormed = 0
        var linesOk = 0
        var labelsOk = 0
        var labelsTotal = 0
        val failures = mutableMapOf<String, Int>()
        val report = StringBuilder()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val n = o.getInt("n")
            val p = Prompt.parse(o.getString("raw"), n)
            if (p.wellFormed) wellFormed++
            val checks = p.line?.let {
                Safety.checkLine(it, o.optString("required_word").takeIf { w -> !o.isNull("required_word") && w.isNotEmpty() },
                    o.getBoolean("about_person"), o.optString("context", ""),
                    o.optString("self_name").takeIf { w -> !o.isNull("self_name") && w.isNotEmpty() },
                    o.optJSONArray("required_words")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                    o.optBoolean("long_line", false))
            }
            val fails = checks?.failures() ?: listOf("no_line")
            if (fails.isEmpty()) linesOk++
            fails.forEach { failures.merge(it.substringBefore('('), 1, Int::plus) }
            for (l in p.labels) { labelsTotal++; if (l != null && Safety.checkLabel(l).allOk()) labelsOk++ }
            report.appendLine("${if (fails.isEmpty()) "OK  " else "FAIL"} ${o.getString("user").lines().first { it.startsWith("saw") || it.startsWith("event") }} -> ${p.line}  ${if (fails.isEmpty()) "" else fails.toString()}")
        }
        val n = items.length()
        report.appendLine("well-formed $wellFormed/$n, lines passing all checks $linesOk/$n, labels ok $labelsOk/$labelsTotal")
        report.appendLine("failure reasons: $failures")
        File(buildDir, "model_eval.txt").writeText(report.toString())
        println(report)
    }
}
