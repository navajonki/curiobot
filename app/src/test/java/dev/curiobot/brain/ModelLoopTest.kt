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
        val intentsFresh = listOf(Intent.ASK_ABOUT, Intent.CORRECT, Intent.SHOW_NEW, Intent.MENU)
        val intentsIdle = listOf(Intent.SHOW_NEW, Intent.HUNT, Intent.WISH, Intent.REMEMBER)
        val intentsPerson = listOf(Intent.SAY_HI, Intent.SHOW_NEW, Intent.MENU)
        val cases = listOf(
            Triple(Mood.DELIGHTED, Event.Saw("dog", true, 1), intentsFresh),
            Triple(Mood.CURIOUS, Event.Saw("bicycle", true, 1), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Saw("banana", true, 1), intentsFresh),
            Triple(Mood.BORED, Event.Saw("chair", false, 6), intentsFresh),
            Triple(Mood.FRIENDLY, Event.SawPerson(true), intentsPerson),
            Triple(Mood.CURIOUS, Event.About("mug"), intentsFresh),
            Triple(Mood.CONTENT, Event.Recall(JournalEntry(0, "tiger", "delighted", "Day 2: first tiger. Loved it.")), intentsIdle),
            Triple(Mood.CURIOUS, Event.Taught("cookie jar"), intentsFresh),
            Triple(Mood.CURIOUS, Event.Taught(MYSTERY), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Found("pillow", "something soft"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Found("spoon", "a spoon"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.WishGranted("apple", "something to eat"), intentsFresh),
            Triple(Mood.DELIGHTED, Event.Named("mug", "Mugsby"), intentsFresh),
            Triple(Mood.CONTENT, Event.Saw("mug", false, 5, "Mugsby"), intentsFresh),
            Triple(Mood.CONTENT, Event.Woke, intentsIdle),
        )
        for (seed in listOf(11L, 222L, 3333L)) {
            val t = Genome.traitsFor(seed)
            for ((mood, ev, intents) in cases) {
                val spec = Prompt.build(t, mood, ev, intents)
                out.put(JSONObject().apply {
                    put("system", spec.system)
                    put("examples", JSONArray(spec.examples.map { JSONArray(listOf(it.first, it.second)) }))
                    put("user", spec.user)
                    put("required_word", spec.requiredWord ?: JSONObject.NULL)
                    put("about_person", spec.aboutPerson)
                    put("n", spec.intents.count { it.modelLabel })
                })
            }
        }
        File(buildDir, "prompt_fixtures.json").writeText(out.toString(2))
    }

    @Test fun evaluateOutputs() {
        val f = File(buildDir, "model_outputs.json")
        assumeTrue("run tools/model/run_fixtures.py first", f.exists())
        val items = JSONArray(f.readText())
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
                    o.getBoolean("about_person"))
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
