package com.steve1316.uma_android_automation.bot.career_setup

import net.ricecode.similarity.LevenshteinDistanceStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl

/** A catalog card the game shows as "[Title] Name". */
interface CatalogCard {
    /** The character name. */
    val name: String

    /** The card title in brackets. */
    val title: String

    /** The card as the game shows it, such as "[Edomurasaki] Inari One". */
    val label: String get() = "$title $name"
}

/** One trainee outfit from `traineeCards.json`. */
data class TraineeCard(
    /** GameTora card id, which is also the stored `careerSetup.trainee` value. */
    val id: String,
    /** The character name, such as "Inari One". */
    override val name: String,
    /** The outfit title in brackets, such as "[Edomurasaki]". */
    override val title: String,
    /** Grades keyed by "turf", "dirt", "sprint", "mile", "medium", "long", "front", "pace", "late" and "end". */
    val aptitudes: Map<String, String>,
    /** Growth bonuses in percent keyed by "speed", "stamina", "power", "guts" and "wit". */
    val growth: Map<String, Int>,
) : CatalogCard

/** One support card from `supportCards.json`. */
data class SupportCard(
    /** GameTora support id, which is also the stored `careerSetup.friendCard` value. */
    val id: String,
    /** The character name, such as "Maruzensky". */
    override val name: String,
    /** The card title in brackets, such as "[Fire at My Heels]". */
    override val title: String,
    /** The card type: Speed, Stamina, Power, Guts, Wit, Friend or Group. */
    val type: String,
    /** The rarity: R, SR or SSR. */
    val rarity: String,
) : CatalogCard

/** A scenario's friend card favorites from `careerFriendFavorites.json`. */
data class FriendFavorites(
    /** Support ids to borrow, most wanted first. */
    val favorites: List<String>,
    /** Card types the scenario wants, most wanted first, used to fill the deck's gap. */
    val types: List<String>,
)

/** Loose matching for names and titles read by OCR on the Career Setup screens. */
object NameMatching {
    /** The similarity a name must reach to match. */
    private const val NAME_SIMILARITY = 0.85

    private val levenshtein = StringSimilarityServiceImpl(LevenshteinDistanceStrategy())

    /**
     * Whether an OCR'd name is the wanted name, allowing small OCR slips.
     *
     * @param ocr The text read off the screen.
     * @param wanted The name from settings. Empty matches anything.
     * @returns True if the names match.
     */
    fun matches(ocr: String, wanted: String): Boolean {
        val a = normalize(ocr)
        val b = normalize(wanted)
        if (b.isEmpty()) return true
        if (a.isEmpty()) return false
        return a == b || levenshtein.score(a, b) >= NAME_SIMILARITY
    }

    /**
     * How alike two strings are, ignoring case, spaces and punctuation.
     *
     * @param ocr The text read off the screen.
     * @param wanted The text to compare with.
     * @returns 1.0 for equal strings, falling toward 0.0 as more edits are needed.
     */
    fun similarity(ocr: String, wanted: String): Double = levenshtein.score(normalize(ocr), normalize(wanted))

    /**
     * Parses the number out of a "Lvl 50" label.
     *
     * @param ocr The text read off the screen.
     * @returns The level, or null if no level number was found.
     */
    fun parseLevel(ocr: String): Int? = LEVEL_REGEX.find(ocr.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Matches the trailing level number of a "Lvl 50" label. */
    private val LEVEL_REGEX = Regex("""(\d{1,2})\s*$""")

    /**
     * Lowercases a string and drops everything but letters and digits.
     *
     * @param text The text to normalize.
     * @returns The normalized text.
     */
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
}

/** Matches text read off the screen against the Career Setup catalogs. */
object CareerCatalog {
    /**
     * Finds the card shown on screen, such as the outfit on Trainee Select or a Borrow Card row.
     *
     * @param ocrTitle The title line read off the screen.
     * @param ocrName The name line read off the screen.
     * @param cards The catalog to search.
     * @returns The card, or null if none has that name and title.
     */
    fun <T : CatalogCard> match(ocrTitle: String, ocrName: String, cards: Collection<T>): T? =
        cards.filter { NameMatching.matches(ocrName, it.name) }.maxByOrNull { NameMatching.similarity(ocrTitle, it.title) }?.takeIf { NameMatching.matches(ocrTitle, it.title) }

    /**
     * Picks the card type a borrowed friend card should be, from the types the scenario wants and the types the deck already holds.
     *
     * @param wantedTypes The scenario's wanted types, most wanted first.
     * @param deckCounts How many cards of each type the deck holds. A missing type counts as zero.
     * @returns The wanted type the deck holds the fewest of, or null if the scenario lists no types.
     */
    fun deckGapType(wantedTypes: List<String>, deckCounts: Map<String, Int>): String? = wantedTypes.minByOrNull { deckCounts[it] ?: 0 }

    /** The order of the game's Name sort: plain alphabetical by character name, ignoring case. */
    val gameNameOrder: Comparator<String> = Comparator { a, b -> a.lowercase().compareTo(b.lowercase()) }

    /**
     * Decides which end of the Name-sorted trainee list to search from, so the search covers at most about half the list.
     *
     * @param target The wanted trainee's character name.
     * @param names Every trainee name in the catalog, used to estimate where the target sits in the list.
     * @returns True to sort descending, because the target is in the later half of the alphabet.
     */
    fun searchDescending(target: String, names: Collection<String>): Boolean {
        val sorted = names.distinct().sortedWith(gameNameOrder)
        val before = sorted.count { gameNameOrder.compare(it, target) < 0 }
        return before * 2 > sorted.size
    }

    /**
     * Checks whether the Smart Race Solver plans for a different trainee than the one picked.
     *
     * @param outfit The picked outfit, or null if it could not be read.
     * @param solverCharacter The solver's character preset, or null while the solver is off.
     * @returns The solver's character when it differs from the picked trainee, else null.
     */
    fun solverMismatch(outfit: TraineeCard?, solverCharacter: String?): String? =
        solverCharacter?.takeIf { outfit != null && !it.equals(outfit.name, ignoreCase = true) }

    /**
     * Compares a name with the wanted one in the order of a Name-sorted list.
     *
     * @param name The name of a card in the list.
     * @param target The wanted trainee's name.
     * @param descending True when the list is sorted Z to A.
     * @returns Negative when the name comes before the target in the list, 0 for the same name, and positive when it comes after.
     */
    fun listOrder(name: String, target: String, descending: Boolean): Int = gameNameOrder.compare(name, target).let { if (descending) -it else it }

    /**
     * Finds the catalog name that a name read by OCR stands for.
     *
     * @param ocrName The name line read off the screen.
     * @param names The catalog's trainee names.
     * @returns The closest matching name, or null if none matches.
     */
    fun matchName(ocrName: String, names: Collection<String>): String? =
        names.filter { NameMatching.matches(ocrName, it) }.maxByOrNull { NameMatching.similarity(ocrName, it) }
}
