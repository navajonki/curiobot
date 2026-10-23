package dev.curiobot.brain

import kotlin.random.Random

/**
 * Quests: a three-step goal ("Mugsby's party: something to eat, something
 * that glows, something to play with") finished by showing it things, in any
 * order, with the camera as usual. Finishing one puts on a little party
 * scene built from what you found and unlocks a hat for the creature.
 *
 * Every step is one of the safe, at-home hunts, and nothing bad happens if a
 * quest is never finished: it just waits, or you give it up.
 */
object Quests {
    data class Template(val id: String, val name: String, val steps: List<String>, val hat: String)

    /** "{friend}'s" becomes a nicknamed thing's name when it has one. */
    val TEMPLATES = listOf(
        Template("party", "{friend}'s party", listOf("eat", "glow", "play"), "party"),
        Template("nap", "the coziest nap", listOf("soft", "sit", "wear"), "nightcap"),
        Template("art", "art day", listOf("draw", "pages", "green"), "beret"),
        Template("tea", "a fancy tea party", listOf("drink", "kitchen", "eat"), "tophat"),
        Template("music", "a little concert", listOf("music", "gadget", "round"), "headphones"),
        Template("explore", "the explorer kit", listOf("wear", "word:backpack", "word:water bottle"), "explorer"),
    )

    data class Step(val hunt: String, val found: String? = null)

    class Active(val template: Template, val name: String, val steps: List<Step>) {
        val done get() = steps.count { it.found != null }
        val complete get() = steps.all { it.found != null }
        val title get() = name.replaceFirstChar { it.uppercase() }
    }

    fun active(s: CreatureState): Active? {
        val t = TEMPLATES.firstOrNull { it.id == s.questId } ?: return null
        if (s.questSteps.size != t.steps.size) return null
        return Active(t, s.questName, s.questSteps.toList())
    }

    /** A quest not done recently, preferring ones whose hat it doesn't have yet. */
    fun pick(s: CreatureState, nicknames: Map<String, String>, rng: Random, now: Long): Active {
        val fresh = TEMPLATES.filter { it.id !in s.recentQuests && it.id != s.questId }
        val pool = fresh.filter { it.hat !in s.hats }.ifEmpty { fresh }.ifEmpty { TEMPLATES }
        val t = pool[rng.nextInt(pool.size)]
        val friends = nicknames.values.toList()
        val friend = if (friends.isEmpty()) null else friends[rng.nextInt(friends.size)]
        s.questId = t.id
        s.questName = t.name.replace("{friend}'s", friend?.let { "$it's" } ?: "a tiny")
        s.questSteps.clear(); s.questSteps += t.steps.map { Step(it) }
        s.questStartedAt = now
        return active(s)!!
    }

    /** If this sighting fills an open step, mark it and return the step. One thing fills one step. */
    fun progress(s: CreatureState, word: String, cat: String): Step? {
        if (active(s) == null || word == MYSTERY) return null
        if (s.questSteps.any { it.found == word }) return null
        val i = s.questSteps.indexOfFirst { it.found == null && Hunts.byId(it.hunt)?.matches(word, cat) == true }
        if (i < 0) return null
        s.questSteps[i] = s.questSteps[i].copy(found = word)
        return s.questSteps[i]
    }

    /** A correction: the thing that filled a step wasn't really that. */
    fun undo(s: CreatureState, word: String) {
        val i = s.questSteps.indexOfFirst { it.found == word }
        if (i >= 0) s.questSteps[i] = s.questSteps[i].copy(found = null)
    }

    /** All steps done: unlock the hat and put it on, note it in the journal, clear the quest. */
    fun finish(s: CreatureState, now: Long): Active? {
        val a = active(s)?.takeIf { it.complete } ?: return null
        Hats.unlock(s, a.template.hat)
        s.questsDone++
        s.recentQuests += a.template.id
        while (s.recentQuests.size > 3) s.recentQuests.removeAt(0)
        s.addJournal(JournalEntry(now, a.steps.last().found, Mood.DELIGHTED.word,
            "Day ${Mind.dayNumber(s, now)}: finished ${a.name} with ${a.steps.mapNotNull { it.found }.joinToString(", ")}. Got ${Hats.phrase(a.template.hat)}."))
        clear(s)
        return a
    }

    fun clear(s: CreatureState) {
        s.questId = null; s.questName = ""; s.questSteps.clear()
    }

    fun stepLabel(st: Step) = Hunts.byId(st.hunt)?.label ?: st.hunt

    /** "something to eat, something that glows and something to play with" */
    fun stepList(a: Active): String {
        val labels = a.steps.map { stepLabel(it) }
        return if (labels.size < 2) labels.joinToString() else labels.dropLast(1).joinToString(", ") + " and " + labels.last()
    }

    val GAVE_UP = listOf("Okay. Maybe another time.", "That's fine. Quests can wait.")
    val DONE = listOf("We did it! {Label} is ready! And I got a {hat}.", "Hooray! {Label}, all done! A {hat} for me!")
}

/**
 * Hats: little pixel accessories, earned by finishing quests or filling a
 * spot in the nest (a chef hat once it has six kitchen things). Wearing one is
 * just for fun; nothing depends on it.
 */
object Hats {
    data class Hat(val id: String, val name: String, val line: String, val phrase: String = withArticle(name))

    val ALL = listOf(
        Hat("party", "party hat", "Party hat on. Let's celebrate!"),
        Hat("nightcap", "nightcap", "My nightcap. So cozy."),
        Hat("beret", "beret", "A beret. I feel very artsy."),
        Hat("tophat", "top hat", "A top hat. Very fancy."),
        Hat("headphones", "headphones", "Headphones on. La la la!", phrase = "headphones"),
        Hat("explorer", "explorer hat", "Explorer hat on. Adventure time!"),
        Hat("chef", "chef hat", "Chef hat! Ready to cook."),
        Hat("flowers", "flower crown", "A flower crown. I feel like a garden."),
        Hat("propeller", "propeller cap", "Propeller cap! Whirr!"),
        Hat("bow", "bow", "A bow. So neat."),
    )

    /** A full spot in the nest earns the matching hat. */
    val FOR_KIND = mapOf("kitchen" to "chef", "plant" to "flowers", "toy" to "propeller", "clothing" to "bow")

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /** "{hat}" in a line, with the right article ("an explorer hat"). */
    fun fill(line: String, id: String): String {
        val p = phrase(id)
        return line.replace("A {hat}", p.replaceFirstChar { it.uppercase() }).replace("a {hat}", p).replace("{hat}", name(id))
    }
    fun name(id: String) = byId(id)?.name ?: id
    /** "a party hat", "an explorer hat", "headphones" */
    fun phrase(id: String) = byId(id)?.phrase ?: withArticle(id)

    /** Returns true if this is a new hat. Either way it goes on. */
    fun unlock(s: CreatureState, id: String): Boolean {
        val isNew = s.hats.add(id)
        s.wearing = id
        return isNew
    }

    /** The hat a nest kind earns when its spot fills up, if it's new. */
    fun forFullKind(s: CreatureState, kind: String): String? = FOR_KIND[kind]?.takeIf { it !in s.hats }

    /** Owned hats in wardrobe order. */
    fun owned(s: CreatureState) = ALL.filter { it.id in s.hats }

    const val NONE = "none"
    val NO_HAT = "No hat. Just me."
}
