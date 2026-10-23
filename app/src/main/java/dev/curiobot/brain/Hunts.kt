package dev.curiobot.brain

/**
 * Things the creature can ask for. Every target is safe and usually found at
 * home: no hot appliances, sharp things, ladders, animals or anything outdoors.
 * The asks are written here in code; the text model never makes one up.
 */
data class Hunt(
    val id: String,
    /** Reads after "find me": "something soft", "a spoon". */
    val label: String,
    val words: Set<String> = emptySet(),
    val cats: Set<String> = emptySet(),
) {
    fun matches(word: String, cat: String) = word in words || cat in cats
}

object Hunts {
    val CATEGORY = listOf(
        Hunt("soft", "something soft", words = setOf("pillow", "blanket", "cushion", "teddy bear", "stuffed animal",
            "towel", "sweater", "hoodie", "scarf", "sock", "beanie", "mitten", "slipper", "rug", "yarn", "pajamas")),
        Hunt("round", "something round", words = setOf("ball", "orange", "apple", "plate", "bowl", "clock", "coin",
            "globe", "button", "tomato", "soccer ball", "basketball", "tennis ball", "baseball", "golf ball",
            "donut", "bagel", "pizza", "cookie", "lime", "lemon", "peach")),
        Hunt("eat", "something to eat", cats = setOf("food")),
        Hunt("drink", "something to drink", cats = setOf("drink")),
        Hunt("wear", "something you wear", cats = setOf("clothing")),
        Hunt("play", "something to play with", cats = setOf("toy")),
        Hunt("music", "something that makes music", cats = setOf("music")),
        Hunt("green", "something green and growing", words = setOf("fern", "succulent", "houseplant", "leaf",
            "grass", "moss", "bonsai tree", "vine", "clover", "bamboo", "seedling", "lavender", "orchid", "flower",
            "rose", "tulip", "sunflower", "daisy", "tree")),
        Hunt("sit", "something to sit on", words = setOf("chair", "couch", "stool", "bench", "armchair", "cushion")),
        Hunt("kitchen", "something from the kitchen", words = setOf("mug", "cup", "drinking glass", "plate", "bowl",
            "spoon", "fork", "chopsticks", "cooking pot", "frying pan", "teapot", "cutting board", "whisk", "ladle",
            "spatula", "rolling pin", "jar", "lunchbox", "napkin", "sponge", "oven mitt", "tray", "thermos",
            "measuring cup", "colander", "salt shaker", "cookie jar")),
        Hunt("draw", "something to draw with", words = setOf("pen", "pencil", "crayon", "marker", "chalk",
            "paintbrush", "colored pencils", "watercolors", "paint")),
        Hunt("pages", "something with pages", words = setOf("book", "notebook", "magazine", "newspaper", "sketchbook",
            "calendar")),
        Hunt("gadget", "a gadget", words = setOf("laptop", "computer", "keyboard", "computer mouse", "headphones",
            "earbuds", "speaker", "television", "camera", "tablet", "game controller", "remote control", "radio")),
        Hunt("glow", "something that glows", words = setOf("lamp", "flashlight", "television", "screen", "laptop",
            "computer", "tablet")),
    )

    /** Common, countable, safe things for "I've never seen a ..." hunts. */
    val WORDS = listOf(
        "spoon", "fork", "mug", "cup", "plate", "bowl", "book", "pen", "pencil", "shoe", "sock", "hat", "chair",
        "pillow", "blanket", "lamp", "clock", "key", "apple", "banana", "orange", "toothbrush", "towel", "backpack",
        "ball", "teddy bear", "remote control", "laptop", "headphones", "houseplant", "flower", "leaf", "box",
        "bag", "umbrella", "jar", "water bottle", "basket", "coin", "door", "window", "rug", "shirt", "jacket",
        "notebook", "crayon", "sweater", "scarf", "sponge", "napkin", "carrot", "cookie", "toy car", "doll",
    )

    /** The hunt to offer from a nest kind's page, for kinds that are safe to look for at home. */
    private val FOR_KIND = mapOf("food" to "eat", "drink" to "drink", "clothing" to "wear", "toy" to "play",
        "music" to "music", "plant" to "green", "furniture" to "sit", "kitchen" to "kitchen", "art" to "draw",
        "tech" to "gadget")

    fun forKind(kind: String): Hunt? = FOR_KIND[kind]?.let { byId(it) }

    fun byId(id: String?): Hunt? {
        if (id == null) return null
        CATEGORY.firstOrNull { it.id == id }?.let { return it }
        if (id.startsWith("word:")) {
            val w = id.removePrefix("word:")
            if (w in WORDS) return Hunt(id, withArticle(w), words = setOf(w))
        }
        return null
    }

    /** A hunt that isn't one of the last few, leaning toward words it hasn't seen. */
    fun pick(s: CreatureState, roll: Int): Hunt {
        val recent = s.recentHunts.toSet() + listOfNotNull(s.huntId)
        val unseenWords = WORDS.filter { it !in s.seen && "word:$it" !in recent }
        val cats = CATEGORY.filter { it.id !in recent }
        val wordHunt = unseenWords.isNotEmpty() && (cats.isEmpty() || Math.floorMod(roll, 3) == 0)
        return if (wordHunt) byId("word:" + unseenWords[Math.floorMod(roll / 3, unseenWords.size)])!!
        else (cats.ifEmpty { CATEGORY })[Math.floorMod(roll, cats.ifEmpty { CATEGORY }.size)]
    }

    /** One wish a day, leaning toward what it loves. */
    fun pickWish(s: CreatureState, roll: Int): Hunt {
        val t = s.traits
        val loved = CATEGORY.filter { h -> h.cats.any { it in t.loves } || h.words.any { w -> t.loves.any { CAT_OF[w] == it } } }
        val pool = (if (loved.isNotEmpty() && Math.floorMod(roll, 3) != 0) loved else CATEGORY).filter { it.id != s.wishId }
        return pool.ifEmpty { CATEGORY }[Math.floorMod(roll, pool.ifEmpty { CATEGORY }.size)]
    }


    // Rough categories for word-based hunts, so wishes can follow what it loves.
    private val CAT_OF = mapOf(
        "pillow" to "furniture", "blanket" to "furniture", "cushion" to "furniture", "rug" to "furniture",
        "teddy bear" to "toy", "stuffed animal" to "toy", "ball" to "toy", "fern" to "plant", "leaf" to "plant",
        "flower" to "plant", "houseplant" to "plant", "mug" to "kitchen", "plate" to "kitchen", "bowl" to "kitchen",
        "book" to "home", "pen" to "home", "pencil" to "home", "lamp" to "furniture", "laptop" to "tech",
        "chair" to "furniture", "couch" to "furniture", "apple" to "food", "orange" to "food", "cookie" to "food",
    )
}
