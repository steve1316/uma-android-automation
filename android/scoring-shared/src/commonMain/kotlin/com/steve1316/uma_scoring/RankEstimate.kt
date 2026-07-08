package com.steve1316.uma_scoring

import kotlin.math.floor

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Estimated overall trainee rank

/**
 * Estimated overall trainee rank, ported from the open-source daftuyda/UmaTools calculator (js/rating-shared.js and js/calculator.js). The total is
 * `sum(statScore) + uniqueBonus + sum(skillScore)` mapped onto a rank tier. This mirrors UmaTools' output - an approximation of the game's unpublished formula - so results are
 * labeled "Est." in the UI and are not the exact in-game number. Unlike the rest of this module it is consumed by the JVM bot only, so it is not `@JsExport`-ed.
 *
 * Skill scoring is data-free: the bot supplies each owned skill's base evaluation points (`eval_pt` from our own skill database) and a `checkType` derived from the skill's
 * activation condition. UmaTools' per-skill values equal `base x {S/A 1.1, B/C 0.9, D/E/F 0.8, else 0.7}` for the vast majority of skills, so we apply that multiplier here
 * against the base rather than shipping a bundled dataset.
 */

// Per-point stat-score rates for the 1-1200 range, in 50-value blocks. Ported verbatim from UmaTools rating-shared.js STAT_SCORES (R1).
private val STAT_RATE_LOW =
    intArrayOf(
        5, 8, 10, 13, 16, 18, 21, 24, 26, 28, 29, 30, 31, 33, 34, 35, 39, 41, 42, 43, 52, 55, 66, 68, 68,
    )

// Per-point stat-score rates for the 1201-2000 range, in 10-value blocks. Ported verbatim from UmaTools rating-shared.js STAT_SCORES (R2).
private val STAT_RATE_MID =
    intArrayOf(
        79, 80, 81, 83, 84, 85, 86, 88, 89, 90, 92, 93, 94, 96, 97, 98, 100, 101, 102, 103, 105, 106, 107, 109, 110, 111, 113, 114, 115, 117, 118, 119, 121, 122, 123, 124,
        126, 127, 128, 130, 131, 132, 134, 135, 136, 138, 139, 140, 141, 143, 144, 145, 147, 148, 149, 151, 152, 153, 155, 156, 157, 159, 160, 161, 162, 164, 165, 166, 168,
        169, 170, 172, 173, 174, 176, 177, 178, 179, 181, 182, 182,
    )

// Highest stat value the score curve is defined for. Values above this are clamped down to it, values below 0 up to 0.
private const val MAX_STAT_VALUE = 2500

// Aptitude-bucket multipliers applied to a skill's base evaluation points, keyed off the trainee's aptitude grade for the skill's checkType.
private const val MULT_GOOD = 1.1
private const val MULT_AVERAGE = 0.9
private const val MULT_BAD = 0.8
private const val MULT_TERRIBLE = 0.7

// Which aptitude group each affinity role belongs to. A multi-role skill takes the best multiplier per group, then multiplies the groups together.
private val ROLE_GROUP =
    mapOf(
        "turf" to "surface", "dirt" to "surface",
        "sprint" to "distance", "mile" to "distance", "medium" to "distance", "long" to "distance",
        "front" to "style", "pace" to "style", "late" to "style", "end" to "style",
    )

// Minimum total score for each rank tier, index-aligned with RANK_LABELS. Ported verbatim from UmaTools RATING_BADGE_MINIMA (298 tiers, G..LS24).
private val RANK_MINS =
    intArrayOf(
        0, 300, 600, 900, 1300, 1800, 2300, 2900, 3500, 4900, 6500, 8200,
        10000, 12100, 14500, 15900, 17500, 19200, 19600, 20000, 20400, 20800, 21200, 21600,
        22100, 22500, 23000, 23400, 23900, 24300, 24800, 25300, 25800, 26300, 26800, 27300,
        27800, 28300, 28800, 29400, 29900, 30400, 31000, 31500, 32100, 32700, 33200, 33800,
        34400, 35000, 35600, 36200, 36800, 37500, 38100, 38700, 39400, 40000, 40700, 41300,
        42000, 42700, 43400, 44000, 44700, 45400, 46200, 46900, 47600, 48300, 49000, 49800,
        50500, 51300, 52000, 52800, 53600, 54400, 55200, 55900, 56700, 57500, 58400, 59200,
        60000, 60800, 61700, 62500, 63400, 64200, 65100, 66400, 67700, 69000, 70300, 71600,
        72900, 74400, 76000, 76600, 77200, 77800, 78500, 79100, 79700, 80400, 81000, 81700,
        82300, 83000, 83600, 84300, 84900, 85600, 86200, 86700, 87300, 87900, 88500, 89100,
        89700, 90300, 90900, 91400, 92000, 92600, 93200, 93800, 94400, 95000, 95600, 96300,
        96900, 97500, 98000, 98500, 99000, 99600, 100100, 100600, 101100, 101700, 102200, 102700,
        103200, 103800, 104300, 104800, 105400, 105900, 106400, 106900, 107500, 108000, 108500, 109100,
        109600, 110100, 110700, 111200, 111800, 112300, 112800, 113400, 113900, 114400, 115000, 115500,
        116100, 116600, 117100, 117700, 118200, 118800, 119300, 119900, 120400, 121000, 121500, 122000,
        122600, 123100, 123700, 124200, 124800, 125300, 125900, 126400, 127000, 127500, 128100, 128700,
        129200, 129800, 130300, 130900, 131400, 132000, 132500, 133100, 133700, 134200, 134800, 135300,
        135900, 136500, 137000, 137600, 138100, 138700, 139300, 139800, 140400, 141000, 141500, 142100,
        142700, 143200, 143800, 144400, 144900, 145500, 146100, 146600, 147200, 147800, 148400, 148900,
        149500, 150100, 150700, 151200, 151800, 152400, 153000, 153500, 154100, 154700, 155300, 155900,
        156400, 157000, 157600, 158200, 158800, 159300, 159900, 160500, 161100, 161700, 162300, 162900,
        163400, 164000, 164600, 165200, 165800, 166400, 167000, 167600, 168200, 168700, 169300, 169900,
        170500, 171100, 171700, 172300, 172900, 173500, 174100, 174700, 175300, 175900, 176500, 177100,
        177700, 178300, 178900, 179500, 180100, 180700, 181300, 181900, 182500, 183100, 183700, 184300,
        184900, 185500, 186200, 186800, 187400, 188000, 188600, 189200, 189800, 190400,
    )

// Rank labels, index-aligned with RANK_MINS. The array index is also the utx_txt_rank image number (index 0 = plain "G", which has no badge image).
private val RANK_LABELS =
    arrayOf(
        "G", "G+", "F", "F+", "E", "E+", "D", "D+", "C", "C+", "B", "B+",
        "A", "A+", "S", "S+", "SS", "SS+", "UG", "UG1", "UG2", "UG3", "UG4", "UG5",
        "UG6", "UG7", "UG8", "UG9", "UF", "UF1", "UF2", "UF3", "UF4", "UF5", "UF6", "UF7",
        "UF8", "UF9", "UE", "UE1", "UE2", "UE3", "UE4", "UE5", "UE6", "UE7", "UE8", "UE9",
        "UD", "UD1", "UD2", "UD3", "UD4", "UD5", "UD6", "UD7", "UD8", "UD9", "UC", "UC1",
        "UC2", "UC3", "UC4", "UC5", "UC6", "UC7", "UC8", "UC9", "UB", "UB1", "UB2", "UB3",
        "UB4", "UB5", "UB6", "UB7", "UB8", "UB9", "UA", "UA1", "UA2", "UA3", "UA4", "UA5",
        "UA6", "UA7", "UA8", "UA9", "US", "US1", "US2", "US3", "US4", "US5", "US6", "US7",
        "US8", "US9", "LG", "LG1", "LG2", "LG3", "LG4", "LG5", "LG6", "LG7", "LG8", "LG9",
        "LG10", "LG11", "LG12", "LG13", "LG14", "LG15", "LG16", "LG17", "LG18", "LG19", "LG20", "LG21",
        "LG22", "LG23", "LG24", "LF", "LF1", "LF2", "LF3", "LF4", "LF5", "LF6", "LF7", "LF8",
        "LF9", "LF10", "LF11", "LF12", "LF13", "LF14", "LF15", "LF16", "LF17", "LF18", "LF19", "LF20",
        "LF21", "LF22", "LF23", "LF24", "LE", "LE1", "LE2", "LE3", "LE4", "LE5", "LE6", "LE7",
        "LE8", "LE9", "LE10", "LE11", "LE12", "LE13", "LE14", "LE15", "LE16", "LE17", "LE18", "LE19",
        "LE20", "LE21", "LE22", "LE23", "LE24", "LD", "LD1", "LD2", "LD3", "LD4", "LD5", "LD6",
        "LD7", "LD8", "LD9", "LD10", "LD11", "LD12", "LD13", "LD14", "LD15", "LD16", "LD17", "LD18",
        "LD19", "LD20", "LD21", "LD22", "LD23", "LD24", "LC", "LC1", "LC2", "LC3", "LC4", "LC5",
        "LC6", "LC7", "LC8", "LC9", "LC10", "LC11", "LC12", "LC13", "LC14", "LC15", "LC16", "LC17",
        "LC18", "LC19", "LC20", "LC21", "LC22", "LC23", "LC24", "LB", "LB1", "LB2", "LB3", "LB4",
        "LB5", "LB6", "LB7", "LB8", "LB9", "LB10", "LB11", "LB12", "LB13", "LB14", "LB15", "LB16",
        "LB17", "LB18", "LB19", "LB20", "LB21", "LB22", "LB23", "LB24", "LA", "LA1", "LA2", "LA3",
        "LA4", "LA5", "LA6", "LA7", "LA8", "LA9", "LA10", "LA11", "LA12", "LA13", "LA14", "LA15",
        "LA16", "LA17", "LA18", "LA19", "LA20", "LA21", "LA22", "LA23", "LA24", "LS", "LS1", "LS2",
        "LS3", "LS4", "LS5", "LS6", "LS7", "LS8", "LS9", "LS10", "LS11", "LS12", "LS13", "LS14",
        "LS15", "LS16", "LS17", "LS18", "LS19", "LS20", "LS21", "LS22", "LS23", "LS24",
    )

// Precomputed stat-value -> score table for 0..MAX_STAT_VALUE, built once from the rate arrays above.
private val STAT_SCORES = buildStatScores()

/**
 * The trainee's aptitude letter grade (`G`..`S`) for each of the ten skill-affinity roles. Used to pick the bucket multiplier for an aptitude-linked skill.
 *
 * @property turf Aptitude grade for turf-affinity skills.
 * @property dirt Aptitude grade for dirt-affinity skills.
 * @property sprint Aptitude grade for sprint-affinity skills.
 * @property mile Aptitude grade for mile-affinity skills.
 * @property medium Aptitude grade for medium-affinity skills.
 * @property long Aptitude grade for long-affinity skills.
 * @property front Aptitude grade for front-runner-affinity skills.
 * @property pace Aptitude grade for pace-chaser-affinity skills.
 * @property late Aptitude grade for late-surger-affinity skills.
 * @property end Aptitude grade for end-closer-affinity skills.
 */
data class RankAptitudes(
    val turf: String,
    val dirt: String,
    val sprint: String,
    val mile: String,
    val medium: String,
    val long: String,
    val front: String,
    val pace: String,
    val late: String,
    val end: String,
)

/**
 * One owned skill's scoring inputs.
 *
 * @property evalPt The skill's base evaluation points (our `eval_pt`).
 * @property checkType The skill's aptitude affinity, e.g. "Late", "Medium/Long", or "" when the skill scores flat regardless of aptitude.
 */
data class SkillScoreInput(
    val evalPt: Int,
    val checkType: String,
)

/**
 * The result of an estimated-rank computation.
 *
 * @property totalScore The full estimated evaluation score.
 * @property statScore The summed stat-score contribution.
 * @property skillScore The summed skill-score contribution.
 * @property uniqueBonus The unique-skill bonus contribution.
 * @property rankLabel The rank tier label, e.g. "S" or "SS+".
 * @property rankImageIndex The utx_txt_rank image number for the label (0 = plain "G", which has no image).
 */
data class RankResult(
    val totalScore: Int,
    val statScore: Int,
    val skillScore: Int,
    val uniqueBonus: Int,
    val rankLabel: String,
    val rankImageIndex: Int,
)

/**
 * Builds the stat-value -> score lookup, ported from UmaTools rating-shared.js. Three ranges accumulate a running raw total at accelerating per-point rates, and each stored
 * score is the raw total divided by 10 and rounded to the nearest integer (integer `(raw + 5) / 10` matches JS `Math.round(raw / 10)` for non-negative raw).
 *
 * @return An array indexed by stat value in 0..MAX_STAT_VALUE giving each value's score.
 */
private fun buildStatScores(): IntArray {
    val sc = IntArray(MAX_STAT_VALUE + 1)
    var raw = 0
    var idx = 0
    for (c in 1..1200) {
        idx =
            when {
                c <= 49 -> 0
                c <= 99 -> 1
                c % 50 == 0 -> idx + 1
                else -> idx
            }
        raw += STAT_RATE_LOW[idx]
        sc[c] = (raw + 5) / 10
    }
    raw = 38413
    idx = 0
    for (c in 1201..2000) {
        idx =
            when {
                c <= 1209 -> 0
                c <= 1219 -> 1
                c % 10 == 0 -> idx + 1
                else -> idx
            }
        raw += STAT_RATE_MID[idx]
        sc[c] = (raw + 5) / 10
    }
    raw = 142796
    idx = 0
    var rate = 183
    for (c in 2001..MAX_STAT_VALUE) {
        if (idx >= 25) {
            rate++
            idx = 0
        }
        raw += rate
        idx++
        sc[c] = (raw + 5) / 10
    }
    return sc
}

/** Rounds like JS `Math.round` (ties toward positive infinity) for non-negative inputs, which the ported multipliers always produce. */
private fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

/** Maps an aptitude letter grade to its bucket multiplier, following UmaTools `getBucketForGrade`. */
private fun gradeMultiplier(grade: String): Double =
    when (grade.uppercase()) {
        "S", "A" -> MULT_GOOD
        "B", "C" -> MULT_AVERAGE
        "D", "E", "F" -> MULT_BAD
        else -> MULT_TERRIBLE
    }

/** Returns the trainee's aptitude grade for one affinity role, or null when the role is not one of the ten known roles. */
private fun gradeForRole(role: String, apt: RankAptitudes): String? =
    when (role) {
        "turf" -> apt.turf
        "dirt" -> apt.dirt
        "sprint" -> apt.sprint
        "mile" -> apt.mile
        "medium" -> apt.medium
        "long" -> apt.long
        "front" -> apt.front
        "pace" -> apt.pace
        "late" -> apt.late
        "end" -> apt.end
        else -> null
    }

/** Returns the bucket multiplier for one affinity role given the trainee's aptitudes, or null when the role is unknown. */
private fun roleMultiplier(role: String, apt: RankAptitudes): Double? {
    val grade = gradeForRole(role, apt) ?: return null
    return gradeMultiplier(grade)
}

/** Finds the highest rank tier whose minimum the score meets. RANK_MINS is ascending, so this scans until the score drops below a tier's minimum. */
private fun rankIndexForScore(totalScore: Int): Int {
    if (totalScore <= 0) return 0
    var idx = 0
    for (i in RANK_MINS.indices) {
        if (totalScore >= RANK_MINS[i]) idx = i else break
    }
    return idx
}

/**
 * The evaluation-score contribution of a single stat value.
 *
 * @param value The raw stat value (clamped to 0..MAX_STAT_VALUE).
 * @return The stat's score contribution.
 */
fun statScore(value: Int): Int = STAT_SCORES[value.coerceIn(0, MAX_STAT_VALUE)]

/**
 * The unique-skill bonus, following UmaTools `calcUniqueBonus`. The app does not read star rarity, so it always uses the x170 multiplier.
 *
 * @param uniqueLevel The unique skill's level.
 * @return The bonus points, or 0 when the unique level is not positive.
 */
fun uniqueBonus(uniqueLevel: Int): Int {
    if (uniqueLevel <= 0) return 0
    return 170 * uniqueLevel
}

/**
 * The rank tier label for a total evaluation score.
 *
 * @param totalScore The total evaluation score.
 * @return The rank label, e.g. "S" or "SS+".
 */
fun scoreToRankLabel(totalScore: Int): String = RANK_LABELS[rankIndexForScore(totalScore)]

/**
 * The utx_txt_rank image number for a rank label.
 *
 * @param label The rank label, e.g. "B+".
 * @return The image index (0 = plain "G", which has no image), or -1 when the label is unknown.
 */
fun rankLabelToImageIndex(label: String): Int = RANK_LABELS.indexOf(label)

/**
 * The evaluation-score contribution of one owned skill, following UmaTools `evaluateSkillScore`. A skill with no checkType scores its flat base. A single-role skill scores
 * `base x bucketMultiplier` for the trainee's aptitude grade in that role. A multi-role skill (e.g. "Medium/Long") takes the best multiplier per group (surface/distance/style)
 * and multiplies the groups together.
 *
 * @param baseEvalPt The skill's base evaluation points.
 * @param checkType The skill's affinity, e.g. "Late" or "Medium/Long", or "" for none.
 * @param aptitudes The trainee's aptitude grades.
 * @return The skill's rounded score contribution.
 */
fun evaluateSkillScore(baseEvalPt: Int, checkType: String, aptitudes: RankAptitudes): Int {
    val ct = checkType.trim().lowercase()
    if (ct.isEmpty()) return baseEvalPt
    if (ct.contains("/")) {
        val groupMax = HashMap<String, Double>()
        for (part in ct.split("/")) {
            val role = part.trim()
            if (role.isEmpty()) continue
            val mult = roleMultiplier(role, aptitudes) ?: continue
            val group = ROLE_GROUP[role] ?: role
            val prev = groupMax[group]
            if (prev == null || mult > prev) groupMax[group] = mult
        }
        if (groupMax.isEmpty()) return baseEvalPt
        var factor = 1.0
        for (m in groupMax.values) factor *= m
        return jsRound(baseEvalPt * factor)
    }
    val mult = roleMultiplier(ct, aptitudes) ?: return baseEvalPt
    return jsRound(baseEvalPt * mult)
}

/**
 * Estimates the trainee's overall rank from stats, owned skills, aptitudes, and the unique skill. `total = sum(statScore) + sum(skillScore) + uniqueBonus`.
 *
 * @param speed The Speed stat.
 * @param stamina The Stamina stat.
 * @param power The Power stat.
 * @param guts The Guts stat.
 * @param wit The Wit stat.
 * @param skills The owned skills' scoring inputs.
 * @param aptitudes The trainee's aptitude grades.
 * @param uniqueLevel The unique skill's level.
 * @return The estimated rank, score, and per-component breakdown.
 */
fun estimateRank(
    speed: Int,
    stamina: Int,
    power: Int,
    guts: Int,
    wit: Int,
    skills: List<SkillScoreInput>,
    aptitudes: RankAptitudes,
    uniqueLevel: Int,
): RankResult {
    val statTotal = statScore(speed) + statScore(stamina) + statScore(power) + statScore(guts) + statScore(wit)
    var skillTotal = 0
    for (s in skills) skillTotal += evaluateSkillScore(s.evalPt, s.checkType, aptitudes)
    val bonus = uniqueBonus(uniqueLevel)
    val total = statTotal + skillTotal + bonus
    val idx = rankIndexForScore(total)
    return RankResult(
        totalScore = total,
        statScore = statTotal,
        skillScore = skillTotal,
        uniqueBonus = bonus,
        rankLabel = RANK_LABELS[idx],
        rankImageIndex = idx,
    )
}
