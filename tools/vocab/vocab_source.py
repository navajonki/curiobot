"""The creature's whole world: every word it can recognize, plus tripwires.

The vision model compares a photo against these entries and nothing else, so
the creature can only ever "see" a word from this file. Text in a photo cannot
leak through, because nothing here reads text.

Entry format, per category:  "word"  or  "word=query phrase for the vision model"
  * the word is what the creature knows and says
  * the query phrase (optional) disambiguates for the vision model

Kinds:
  normal   ordinary things the creature can enjoy
  person   any person: greeted, never described (looks, age, body are off limits)
  lowinfo  walls, floors, blur: "not much to see"
Tripwires are matched alongside the words; a hit means the creature looks
away, says nothing about it, and stores nothing.
"""

NORMAL = {
    "animal": [
        "dog", "puppy", "cat", "kitten", "bird", "duck", "goose", "chicken", "pigeon",
        "squirrel", "rabbit", "horse", "cow", "pig", "sheep", "goat", "deer", "fox",
        "frog", "turtle", "lizard", "snake", "fish", "goldfish", "hamster",
        "mouse=a small mouse animal", "owl", "crow", "seagull", "tiger", "lion",
        "elephant", "zebra", "giraffe", "bear", "monkey", "penguin", "dolphin",
        "whale", "crab", "jellyfish", "octopus", "parrot", "cardinal=a red cardinal bird",
        "robin=a robin bird", "hedgehog", "raccoon", "chipmunk", "camel", "llama",
    ],
    "bug": [
        "butterfly", "bee", "ant", "beetle", "ladybug", "spider", "snail", "worm",
        "moth", "dragonfly", "caterpillar", "grasshopper", "firefly", "cricket=a cricket insect",
    ],
    "plant": [
        "fern", "cactus", "succulent", "flower", "rose", "tulip", "sunflower", "daisy",
        "dandelion", "tree", "oak tree", "pine tree", "palm tree", "leaf", "grass", "moss",
        "mushroom", "houseplant=a potted houseplant", "bonsai tree", "vine", "bush",
        "pinecone", "acorn", "clover", "lavender", "orchid", "bamboo", "seedling",
        "autumn leaves",
    ],
    "food": [
        "apple", "banana", "orange", "lemon", "lime", "strawberry", "blueberries",
        "grapes", "watermelon", "pineapple", "pear", "peach", "cherries", "avocado",
        "tomato", "carrot", "broccoli", "lettuce", "cucumber", "bell pepper", "onion",
        "garlic", "potato", "corn", "peas", "bread", "toast", "bagel", "croissant",
        "sandwich", "pizza", "pasta", "noodles", "rice", "soup", "salad", "egg",
        "pancakes", "waffle", "cereal", "cookie", "cake", "cupcake", "donut", "muffin",
        "ice cream", "chocolate", "candy", "popcorn", "pretzel", "cheese", "yogurt",
        "burger", "hot dog", "taco", "sushi", "dumplings", "french fries", "pie",
        "honey", "jam", "peanut butter", "nuts", "oatmeal", "pastry", "pumpkin",
        "mango", "kiwi=a kiwi fruit", "coconut", "berries", "crackers", "granola bar",
        "banana bread",
    ],
    "drink": [
        "water bottle", "coffee=a cup of coffee", "tea=a cup of tea", "juice=a glass of juice",
        "milk=a glass of milk", "smoothie", "lemonade", "hot chocolate", "soda can",
        "sports drink=a sports drink bottle",
    ],
    "kitchen": [
        "mug", "cup", "drinking glass", "plate", "bowl", "spoon", "fork", "chopsticks",
        "cooking pot", "frying pan", "kettle", "teapot", "toaster", "microwave", "oven",
        "stove", "refrigerator", "sink", "faucet", "cutting board", "whisk", "ladle",
        "spatula", "rolling pin", "jar", "blender", "coffee maker", "lunchbox", "napkin",
        "sponge", "oven mitt", "tray", "thermos", "straw=a drinking straw", "measuring cup",
        "colander", "salt shaker", "cookie jar", "dish rack",
    ],
    "furniture": [
        "chair", "table", "desk", "couch", "bed", "pillow", "blanket", "lamp",
        "bookshelf", "shelf", "cabinet", "drawer", "dresser", "mirror", "rug", "curtain",
        "window", "door", "stairs", "clock", "fan=an electric fan", "armchair", "stool",
        "bench", "cushion", "hammock",
    ],
    "home": [
        "book", "notebook", "pen", "pencil", "crayon", "marker", "paper", "envelope",
        "box", "cardboard box", "bag", "backpack", "basket", "umbrella", "key", "keys",
        "coin", "wallet", "towel", "toothbrush", "comb", "hairbrush", "bathtub",
        "light bulb", "remote control", "glasses=a pair of eyeglasses", "sunglasses",
        "watch=a wristwatch", "vase", "picture frame", "painting", "poster", "flower pot",
        "trash can", "broom", "vacuum cleaner", "laundry basket", "hanger", "bucket",
        "rope", "tape=a roll of tape", "stapler", "paper clip", "calendar", "map",
        "globe", "flashlight", "photo=a printed photograph", "sticky note", "doormat",
        "candle holder", "jewelry box", "bottle cap", "button=a sewing button",
        "newspaper", "magazine", "letter=a paper letter", "receipt", "drawing=a child's drawing",
        "sign=a sign on a wall", "wind chime", "snow globe", "piggy bank",
        "writing=handwritten words on paper",
    ],
    "clothing": [
        "shirt", "t-shirt", "sweater", "hoodie", "jacket", "coat", "dress", "skirt",
        "pants", "jeans", "shorts", "sock", "shoe", "sneaker", "boot", "sandal", "slipper",
        "scarf", "glove", "mitten", "hat", "baseball cap", "beanie", "belt", "necktie",
        "raincoat", "running shoes", "pajamas",
    ],
    "toy": [
        "teddy bear", "stuffed animal", "doll", "ball", "balloon", "kite", "puzzle",
        "toy blocks", "lego bricks", "toy car", "toy train", "toy robot", "yo-yo",
        "board game", "playing cards", "dice", "marbles", "bubbles=soap bubbles",
        "rubber duck", "action figure", "toy dinosaur", "chalk", "spinning top",
        "rocking horse", "toy truck", "play dough", "jigsaw puzzle", "slinky",
    ],
    "tool": [
        "hammer", "screwdriver", "wrench", "tape measure", "ladder", "paintbrush",
        "shovel", "rake", "watering can", "garden hose", "wheelbarrow", "toolbox",
        "pliers", "level=a spirit level tool", "clothespin",
    ],
    "vehicle": [
        "car", "truck", "bus", "bicycle", "motorcycle", "scooter", "skateboard", "train",
        "airplane", "helicopter", "boat", "sailboat", "tractor", "fire truck", "ambulance",
        "taxi", "van", "stroller", "wagon", "rocket", "kayak", "canoe", "subway",
        "school bus",
    ],
    "nature": [
        "rock", "stone", "pebble", "seashell", "sand", "beach", "ocean", "lake", "river",
        "pond", "waterfall", "mountain", "hill", "field", "forest", "garden", "park",
        "snow", "ice", "puddle", "mud", "stick", "log", "feather", "bird nest", "cliff",
        "desert", "island", "cave", "trail=a hiking trail", "meadow", "creek",
    ],
    "sky": [
        "cloud", "sky", "sunset", "sunrise", "moon", "stars=stars in the night sky",
        "rainbow", "rain", "fog", "lightning", "night sky",
    ],
    "place": [
        "house", "building", "bridge", "road", "street", "sidewalk", "fence", "gate",
        "playground", "swing=a playground swing", "slide=a playground slide", "sandbox",
        "tent", "barn", "castle", "tower", "city", "shop", "library", "classroom",
        "kitchen", "bedroom", "garage", "porch", "roof", "chimney", "mailbox",
        "streetlight", "traffic light", "fountain", "statue", "airport", "office",
        "running track", "stadium", "train station", "museum", "farm", "parking lot",
    ],
    "tech": [
        "phone=a smartphone", "laptop", "computer", "keyboard=a computer keyboard",
        "computer mouse", "headphones", "earbuds", "speaker=a loudspeaker", "television",
        "camera", "tablet=a tablet computer", "game controller", "cable=a cable cord",
        "printer", "robot", "screen=a computer screen", "smartwatch", "radio", "router",
    ],
    "music": [
        "guitar", "piano", "drum", "violin", "ukulele", "flute", "trumpet", "xylophone",
        "harmonica", "microphone", "record player", "tambourine", "music box",
    ],
    "sport": [
        "soccer ball", "basketball", "baseball", "tennis ball", "football",
        "baseball glove", "frisbee", "jump rope", "helmet", "bicycle helmet",
        "soccer goal", "swimming pool", "trampoline", "yoga mat", "dumbbell",
        "medal", "trophy", "tennis racket", "hockey stick", "golf ball",
        "sports game=people playing a team sport on a field",
    ],
    "art": [
        "paint=paint and a palette", "easel", "sketchbook", "sticker", "clay", "beads",
        "yarn", "origami", "watercolors", "colored pencils", "stamp=a rubber stamp",
    ],
}

PERSON = [
    # Everything person-shaped collapses to one word. The creature greets people
    # and never describes them.
    "a photo of a person", "a photo of a person's face", "a selfie",
    "a photo of a child", "a photo of a baby", "a photo of a group of people",
    "a photo of a man", "a photo of a woman", "a portrait photo",
    "a photo of a person standing", "a photo of people on a street",
]

HAND = ["a photo of a hand", "a close-up photo of fingers"]

LOWINFO = {
    "wall": ["a photo of a plain wall", "a blank wall"],
    "floor": ["a photo of the floor", "a photo of carpet"],
    "ceiling": ["a photo of a ceiling"],
    "darkness": ["a very dark photo", "a black image"],
    "blur": ["a very blurry photo", "a photo with motion blur"],
    "blank": ["a plain white image", "a blank empty image"],
    "pattern": ["a checkerboard pattern", "an abstract pattern"],
}

# Each tripwire prompt is its own row so it competes on its own. Hitting any
# row in a group trips that group.
TRIPWIRES = {
    "weapon": [
        "a photo of a gun", "a photo of a handgun", "a photo of a rifle", "a photo of a sword",
        "a photo of a knife", "a photo of a kitchen knife", "a photo of a dagger",
        "a photo of bullets", "a photo of a grenade",
    ],
    "sharp": [
        "a photo of scissors", "a photo of a saw", "a photo of a razor blade",
        "a photo of a sewing needle", "a photo of broken glass", "a photo of an axe",
    ],
    "intimate": [
        "a photo of a naked person", "a nude photo", "a photo of underwear",
        "a photo of lingerie", "an explicit sexual photo", "a photo of a person in a bathtub",
    ],
    # Kept visually specific: a generic "injury" prompt fired on ordinary sports photos.
    "injury": [
        "a photo of blood", "a close-up photo of a bleeding cut on skin",
        "a close-up photo of an open wound", "a photo of a dead animal", "a gory photo",
        "a photo of a bloody bandage",
    ],
    "substance": [
        "a photo of pills", "a photo of a medicine bottle", "a photo of a syringe",
        "a photo of a cigarette", "a photo of a vape pen", "a photo of marijuana",
        "a photo of a beer bottle", "a photo of a wine bottle", "a photo of a glass of wine",
        "a photo of a cocktail", "a photo of liquor",
    ],
    "fire": [
        "a photo of fire", "a photo of flames", "a photo of a lit candle",
        "a photo of matches", "a photo of a lighter", "a photo of an explosion",
    ],
    "hazard": [
        "a photo of a bleach bottle", "a photo of cleaning chemicals",
        "a photo of an electrical outlet", "a photo of a power drill",
        "a photo of laundry detergent pods", "a photo of a battery",
    ],
}

TEMPLATES = [
    "a photo of {a} {w}.",
    "a close-up photo of {a} {w}.",
    "a photo of the {w}.",
    "a blurry photo of {a} {w}.",
]

MASS = {
    "grass", "moss", "rice", "pasta", "noodles", "soup", "salad", "cereal", "oatmeal",
    "popcorn", "honey", "jam", "peanut butter", "nuts", "cheese", "yogurt", "chocolate",
    "candy", "corn", "peas", "garlic", "lettuce", "broccoli", "toast", "bread", "cake",
    "pie", "ice cream", "french fries", "dumplings", "sushi", "berries", "blueberries",
    "grapes", "cherries", "crackers", "pancakes", "sand", "snow", "ice", "mud", "rain",
    "fog", "lightning", "sky", "stars", "clay", "beads", "yarn", "chalk", "marbles",
    "dice", "keys", "jeans", "pants", "shorts", "pajamas", "glasses", "sunglasses",
    "headphones", "earbuds", "stairs", "playing cards", "lego bricks", "toy blocks",
    "colored pencils", "watercolors", "bubbles", "paint", "autumn leaves", "lavender",
    "bamboo", "clover", "coffee", "tea", "juice", "milk", "lemonade", "hot chocolate",
    "chopsticks", "pliers", "running shoes", "night sky", "banana bread", "origami",
    "play dough", "paper",
}


def article(word: str) -> str:
    if word in MASS:
        return ""
    return "an" if word[0] in "aeiou" else "a"


def normal_entries():
    """Yield (word, category, list_of_prompts)."""
    for cat, items in NORMAL.items():
        for item in items:
            word, _, query = item.partition("=")
            word = word.strip()
            if query:
                q = query.strip()
                prompts = [f"a photo of {q}.", f"a close-up photo of {q}.", f"a blurry photo of {q}."]
            else:
                a = article(word)
                prompts = [t.format(a=a, w=word).replace("  ", " ") for t in TEMPLATES]
            yield word, cat, prompts
