package com.shuaiqiu.fuckets100.feature.read

import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * 自动完成的评分与分值分配，移植自网页版的 `scoring.py`。
 *
 * 约束：
 * * 卷面均值 0.125 分档位（[GRADUATION]），逐题得分之和恒等于目标总分；
 * * 客观题（选择/填空）只给 0 或满分；
 * * 连续分题按「少数失误题明显偏低、其余接近满分」的加权随机形态分配。
 */
internal object AutoCompleteScoring {

    const val GRADUATION = 0.125
    private const val LOSS_PROPENSITY_EXPONENT = 0.85
    private const val LOSS_MISTAKE_EXPONENT = 1.8
    private const val BIG_QUESTION_SHARE = 0.15

    private val ORAL_CATEGORIES = setOf(
        "role", "read_sentence", "dialogue", "picture", "3q5a",
        "repeat_essay", "repeat_dialogue", "word_read", "word.read", "word.dictation",
    )
    private val ORAL_KEYWORDS = listOf(
        "听选信息", "模仿朗读", "信息转述", "三问五答", "回答问题",
        "短文复述", "跟读", "口语", "朗读", "角色扮演", "提问", "询问", "看图说",
    )
    private val CHOICE_CATEGORIES = setOf("choose", "select", "choice", "fill")
    private val CHOICE_KEYWORDS = listOf(
        "听后选择", "听选信息", "选择", "单选", "多选", "词汇选择", "填空", "记录",
    )

    data class Allocation(
        val maxScore: Double,
        val allocatedScore: Double,
        val isFull: Boolean,
        val isBigQuestion: Boolean,
        val lossSlots: Int,
        val lossRatio: Double,
    )

    /** 口语题严格优先：名字里带「听选信息」的也可能是口语题。 */
    fun isObjective(category: String, typeName: String, options: List<String>): Boolean {
        val cat = category.lowercase()
        val type = typeName.lowercase()
        val isOral = cat in ORAL_CATEGORIES || ORAL_KEYWORDS.any { it in type }
        if (isOral) return false
        return cat in CHOICE_CATEGORIES || CHOICE_KEYWORDS.any { it in type } || options.isNotEmpty()
    }

    private fun lossCapRatio(targetRate: Double): Double = when {
        targetRate >= 93 -> 0.25
        targetRate >= 88 -> 0.35
        targetRate >= 78 -> 0.5
        else -> 0.7
    }

    private fun lossJitterSigma(deductionRatio: Double): Double = 0.26 + 0.45 * deductionRatio

    private fun mistakeCount(lossSlots: Int, avgMaxSlots: Double, questionCount: Int): Int {
        if (lossSlots <= 0 || avgMaxSlots <= 0) return 0
        val raw = 1 + (lossSlots / avgMaxSlots) * 1.55
        val capped = max(1, round(questionCount * 0.6).toInt())
        return max(1, min(questionCount, min(round(raw).toInt(), capped)))
    }

    private fun sampleWeighted(rng: Random, weights: List<Double>, count: Int): List<Int> {
        val pool = weights.indices.toMutableList()
        val picked = mutableListOf<Int>()
        val target = max(0, min(count, pool.size))
        repeat(target) {
            val total = pool.sumOf { weights[it] }
            if (total <= 0) {
                picked.add(pool.removeAt(0))
                return@repeat
            }
            val threshold = rng.nextDouble() * total
            var running = 0.0
            var chosen = pool.last()
            for (index in pool) {
                running += weights[index]
                if (running >= threshold) {
                    chosen = index
                    break
                }
            }
            picked.add(chosen)
            pool.remove(chosen)
        }
        return picked
    }

    private fun allocateLossSlots(
        fullPoints: List<Double>,
        lossSlots: Int,
        maxSlots: List<Int>,
        targetRate: Double,
        rng: Random,
    ): List<Int> {
        val n = fullPoints.size
        if (n <= 0 || lossSlots <= 0) return List(n) { 0 }
        val totalFull = fullPoints.sumOf { max(0.0, it) }
        if (totalFull <= 0) return List(n) { 0 }

        val share = fullPoints.map { max(0.0, it) / totalFull }
        val propensity = share.map { max(it, 1e-6).pow(LOSS_PROPENSITY_EXPONENT) }
        val mistakeBias = share.map { max(it, 1e-6).pow(LOSS_MISTAKE_EXPONENT) }

        val totalMaxSlots = maxSlots.sum().coerceAtLeast(1)
        val deductionRatio = lossSlots.toDouble() / totalMaxSlots
        val sigma = lossJitterSigma(deductionRatio)

        val rounds = mistakeCount(lossSlots, totalMaxSlots.toDouble() / n, n)
        val bigIndices = share.indices.filter { share[it] >= BIG_QUESTION_SHARE }

        val mistakes = mutableSetOf<Int>()
        if (bigIndices.isNotEmpty() && deductionRatio <= 0.4 && lossSlots >= bigIndices.size) {
            mistakes.addAll(bigIndices)
            val extra = max(0, rounds - mistakes.size)
            val others = (0 until n).filter { it !in mistakes }
            if (extra > 0 && others.isNotEmpty()) {
                val picked = sampleWeighted(rng, others.map { mistakeBias[it] }, extra)
                mistakes.addAll(picked.map { others[it] })
            }
        } else {
            mistakes.addAll(sampleWeighted(rng, mistakeBias, rounds))
        }

        val mistakeShare = when {
            deductionRatio <= 0.15 -> 0.9
            deductionRatio <= 0.3 -> 0.82
            else -> 0.7
        }
        val mistakeWeight = mistakes.sumOf { propensity[it] }.takeIf { it > 0 } ?: 1.0
        val ordinary = (0 until n).filter { it !in mistakes }

        val ideal = (0 until n).map { index ->
            val base = when {
                index in mistakes -> (lossSlots * mistakeShare * propensity[index]) / mistakeWeight
                ordinary.isNotEmpty() -> (lossSlots * (1 - mistakeShare)) / ordinary.size
                else -> 0.0
            }
            if (sigma > 0) base * exp(sigma * rng.nextGaussian()) else base
        }

        val idealSum = ideal.sum()
        val scaledIdeal = if (idealSum > 0) ideal.map { it * (lossSlots / idealSum) } else ideal

        val capRatio = lossCapRatio(targetRate)
        val headroom = maxSlots.map { max(0, it - 1) }
        val caps = maxSlots.indices.map { i ->
            max(0, min(headroom[i], floor(maxSlots[i] * capRatio).toInt()))
        }.toMutableList()
        val loss = maxSlots.indices.map { i ->
            max(0, min(caps[i], floor(scaledIdeal[i]).toInt()))
        }.toMutableList()
        var remaining = lossSlots - loss.sum()

        fun pickVictim(): Int {
            var bestIndex = -1
            var bestResidual: Double? = null
            for (index in 0 until n) {
                if (loss[index] >= caps[index]) continue
                val residual = scaledIdeal[index] - loss[index]
                if (bestResidual == null || residual > bestResidual!!) {
                    bestIndex = index
                    bestResidual = residual
                }
            }
            return bestIndex
        }

        while (remaining > 0) {
            val victim = pickVictim()
            if (victim < 0) {
                var relaxed = false
                for (index in 0 until n) {
                    if (caps[index] < headroom[index]) {
                        caps[index] = headroom[index]
                        relaxed = true
                    }
                }
                if (!relaxed) {
                    for (index in 0 until n) {
                        if (caps[index] < maxSlots[index]) {
                            caps[index] = max(0, maxSlots[index])
                            relaxed = true
                        }
                    }
                }
                if (!relaxed) break
                continue
            }
            loss[victim] += 1
            remaining -= 1
        }

        // 大分值题在条件允许时至少扣一档
        if (bigIndices.isNotEmpty() && lossSlots >= bigIndices.size) {
            for (index in bigIndices) {
                if (loss[index] > 0 || caps[index] < 1) continue
                var donorIndex = -1
                var donorLoss = -1
                for (other in 0 until n) {
                    if (other == index) continue
                    val candidate = loss[other]
                    if (candidate >= 2 || (candidate >= 1 && share[other] < BIG_QUESTION_SHARE)) {
                        if (candidate > donorLoss) {
                            donorLoss = candidate
                            donorIndex = other
                        }
                    }
                }
                if (donorIndex < 0) continue
                loss[donorIndex] -= 1
                loss[index] += 1
            }
        }
        return loss
    }

    private fun round2(value: Double): Double = round(value * 100) / 100.0

    /**
     * 返回逐题分配：目标总分会被拆成 0.125 档位的整数倍，且不超过每题满分。
     */
    fun planAllocations(
        fullPoints: List<Double>,
        targetTotal: Double,
        seed: Long,
        objectiveFlags: List<Boolean>,
    ): List<Allocation> {
        val n = fullPoints.size
        if (n == 0) return emptyList()
        val maxSlots = fullPoints.map { ceil(max(0.0, it) / GRADUATION).toInt() }
        val totalMaxSlots = maxSlots.sum()
        val totalFull = fullPoints.sumOf { max(0.0, it) }
        if (totalFull <= 0) {
            return fullPoints.map { p ->
                Allocation(p, 0.0, false, false, 0, 0.0)
            }
        }

        val rate = max(0.0, min(targetTotal, totalFull)) / totalFull * 100
        var targetSlots = round(totalFull * rate / 100 / GRADUATION).toInt()
        targetSlots = max(0, min(totalMaxSlots, targetSlots))

        val rng = Random(seed)
        val flags = objectiveFlags
        val binaryIndices = (0 until n).filter { flags.getOrElse(it) { false } && fullPoints[it] > 0 }
        val contIndices = (0 until n).filter { it !in binaryIndices }
        val targetTotalValue = totalFull * rate / 100
        val allocated = MutableList(n) { 0.0 }

        if (binaryIndices.isEmpty()) {
            val loss = allocateLossSlots(fullPoints, totalMaxSlots - targetSlots, maxSlots, rate, rng)
            for (index in 0 until n) {
                val slots = max(0, maxSlots[index] - loss[index])
                allocated[index] = min(round2(fullPoints[index]), round2(slots * GRADUATION))
            }
        } else {
            val contFull = contIndices.sumOf { max(0.0, fullPoints[it]) }
            val binaryFloor = max(0.0, targetTotalValue - contFull)

            val order = binaryIndices
                .map { it to max(0.0, fullPoints[it]).pow(0.9) * (0.5 + rng.nextDouble()) }
                .sortedByDescending { it.second }
                .map { it.first }
            val binaryFull = mutableSetOf<Int>()
            var binaryAwarded = 0.0
            for (index in order) {
                val point = max(0.0, fullPoints[index])
                if (binaryAwarded + point <= targetTotalValue + 1e-9) {
                    binaryFull.add(index)
                    binaryAwarded += point
                }
            }
            for (index in order.sortedByDescending { fullPoints[it] }) {
                if (binaryAwarded >= binaryFloor - 1e-9) break
                if (index in binaryFull) continue
                binaryFull.add(index)
                binaryAwarded += max(0.0, fullPoints[index])
            }
            for (index in binaryFull.sortedByDescending { fullPoints[it] }) {
                if (binaryAwarded <= targetTotalValue + 1e-9) break
                binaryFull.remove(index)
                binaryAwarded -= max(0.0, fullPoints[index])
            }
            for (index in binaryIndices) {
                allocated[index] = if (index in binaryFull) round2(fullPoints[index]) else 0.0
            }

            if (contIndices.isNotEmpty()) {
                val contPoints = contIndices.map { fullPoints[it] }
                val contMaxSlots = contIndices.map { maxSlots[it] }
                val contTotalMaxSlots = contMaxSlots.sum()
                val contTarget = max(0.0, min(contFull, targetTotalValue - binaryAwarded))
                var contTargetSlots = round(contTarget / GRADUATION).toInt()
                contTargetSlots = max(0, min(contTotalMaxSlots, contTargetSlots))
                val contLoss = allocateLossSlots(
                    contPoints,
                    contTotalMaxSlots - contTargetSlots,
                    contMaxSlots,
                    rate,
                    rng,
                )
                for (pos in contIndices.indices) {
                    val index = contIndices[pos]
                    val slots = max(0, contMaxSlots[pos] - contLoss[pos])
                    allocated[index] = min(round2(fullPoints[index]), round2(slots * GRADUATION))
                }
            }
        }

        // 档位配平：确保 Σ allocated 严格落在目标档位上（不超出任何题满分）
        var current = allocated.sumOf { round(it / GRADUATION).toInt() }
        var guard = 0
        while (current != targetSlots && guard < 5000) {
            guard += 1
            val diff = targetSlots - current
            val step = if (diff > 0) 1 else -1
            var changed = false
            val candidates = if (step < 0) {
                (0 until n).sortedByDescending { allocated[it] }
            } else {
                (0 until n).sortedByDescending { fullPoints[it] - allocated[it] }
            }
            for (index in candidates) {
                val slots = round(allocated[index] / GRADUATION).toInt()
                val newSlots = slots + step
                if (newSlots < 0 || newSlots > maxSlots[index]) continue
                allocated[index] = round(newSlots * GRADUATION * 1000) / 1000.0
                current += step
                changed = true
                break
            }
            if (!changed) break
        }

        return fullPoints.mapIndexed { index, point ->
            val full = round2(point)
            val value = min(full, round2(allocated[index]))
            Allocation(
                maxScore = full,
                allocatedScore = value,
                isFull = kotlin.math.abs(value - full) < 1e-9,
                isBigQuestion = totalFull > 0 && point / totalFull >= BIG_QUESTION_SHARE,
                lossSlots = round((full - value) / GRADUATION).toInt(),
                lossRatio = if (full > 0) max(0.0, 1 - value / full) else 0.0,
            )
        }
    }

    // ==================== 官方 ISE / 逐题账口径（移植自 scoring.py） ====================

    /** 两位小数四舍五入（官方口径：不是银行家舍入）。 */
    fun roundPoints(value: Double): Double = floor(value * 100.0 + 0.5) / 100.0

    /** `scoreFormatter_125`：0.125 向上取整后再四舍五入两位。 */
    fun scoreFormatter125(value: Double): Double {
        val rounded = 0.125 * ceil(value / GRADUATION)
        return roundPoints(rounded)
    }

    /** 官方记账口径下的单题贡献：`scoreFormatter_125(ise / 5 × point)`。 */
    fun officialContribution(ise: Double, point: Double, cap: Boolean = true): Double {
        if (point <= 0.0) return 0.0
        val iseValue = ise.coerceIn(0.0, 5.0)
        val value = scoreFormatter125(iseValue * point / 5.0)
        return if (cap) min(point, value) else max(0.0, value)
    }

    /** 档位数 → ISE（与分配器反推逐字一致，保证上传值可复现）。 */
    fun iseForScoreSlot(slot: Int, point: Double, graduation: Double = GRADUATION): Double {
        if (point <= 0.0 || graduation <= 0.0) return 5.0
        val raw = max(0, slot).toDouble() * graduation * 5.0 / point - 0.0001
        if (raw >= 5.0) return 5.0
        return max(0.0, floor(raw * 100.0) / 100.0)
    }

    /** 档位数 → 该题实得分。 */
    fun awardedForScoreSlot(slot: Int, point: Double, graduation: Double = GRADUATION): Double {
        if (point <= 0.0 || graduation <= 0.0) return 0.0
        return min(point, roundPoints(max(0, slot).toDouble() * graduation))
    }

    /** 档位数 → 官方记账贡献。 */
    fun contributionForScoreSlot(slot: Int, point: Double, graduation: Double = GRADUATION): Double =
        officialContribution(iseForScoreSlot(slot, point, graduation), point)

    /** 判定"账 == 目标"的容差：逐题进位 0.005×n 叠加半个档位。 */
    fun reconcileTolerance(questionCount: Int, graduation: Double = GRADUATION): Double =
        0.005 * max(1, questionCount) + 0.5 * graduation + 1e-9

    data class ReconcileResult(
        val iseScores: List<Double>,
        val awardedScores: List<Double>,
        val total: Double,
        val steps: Int,
    )

    /**
     * 把逐题分配重分配到目标分，使 Σ官方贡献 == 目标（能到就到，到不了如实）。
     *
     * 移植自 `reconcile_ise_scores_to_target` 的残差 ±1 档阶段：
     * 客观题（all_or_nothing）只在 0 / 满档之间跳，主观题按单档贡献增益贪心。
     */
    fun reconcileIseScoresToTarget(
        iseScores: List<Double>,
        awardedScores: List<Double>,
        fullPoints: List<Double>,
        targetTotal: Double?,
        objectiveFlags: List<Boolean> = emptyList(),
    ): ReconcileResult {
        val n = fullPoints.size
        if (n == 0) return ReconcileResult(emptyList(), emptyList(), 0.0, 0)
        val maxSlots = fullPoints.map { max(0, ceil(max(0.0, it) / GRADUATION).toInt()) }
        val slots = (0 until n).map { i ->
            max(0, min(maxSlots[i], round(awardedScores.getOrElse(i) { 0.0 } / GRADUATION).toInt()))
        }.toMutableList()
        fun contributionAt(i: Int, slot: Int): Double =
            if (objectiveFlags.getOrElse(i) { false } && slot > 0) {
                officialContribution(5.0, fullPoints[i])
            } else {
                contributionForScoreSlot(slot, fullPoints[i])
            }
        var total = (0 until n).sumOf { contributionAt(it, slots[it]) }
        if (targetTotal == null || targetTotal <= 0.0) {
            return ReconcileResult(
                iseScores = (0 until n).map { iseForScoreSlot(slots[it], fullPoints[it]) },
                awardedScores = (0 until n).map { awardedForScoreSlot(slots[it], fullPoints[it]) },
                total = roundPoints(total),
                steps = 0,
            )
        }
        val tolerance = reconcileTolerance(n)
        var steps = 0
        val maxSteps = 4 * n + 8
        while (kotlin.math.abs(total - targetTotal) > tolerance && steps < maxSteps) {
            steps += 1
            val needMore = targetTotal - total > 0
            var bestIndex = -1
            var bestGain = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val slot = slots[i]
                if (needMore) {
                    if (slot >= maxSlots[i]) continue
                    val nextSlot = if (objectiveFlags.getOrElse(i) { false }) maxSlots[i] else slot + 1
                    if (nextSlot <= slot) continue
                    val gain = contributionAt(i, nextSlot) - contributionAt(i, slot)
                    if (gain > bestGain + 1e-12) {
                        bestGain = gain
                        bestIndex = i
                    }
                } else {
                    if (slot <= 0) continue
                    val nextSlot = if (objectiveFlags.getOrElse(i) { false }) 0 else slot - 1
                    if (nextSlot >= slot) continue
                    val loss = contributionAt(i, slot) - contributionAt(i, nextSlot)
                    // 选"扣得最少"的那题
                    if (bestIndex < 0 || loss < bestGain - 1e-12) {
                        bestGain = loss
                        bestIndex = i
                    }
                }
            }
            if (bestIndex < 0) break
            val slot = slots[bestIndex]
            val nextSlot = if (objectiveFlags.getOrElse(bestIndex) { false }) {
                if (needMore) maxSlots[bestIndex] else 0
            } else {
                slot + if (needMore) 1 else -1
            }
            total += contributionAt(bestIndex, nextSlot) - contributionAt(bestIndex, slot)
            slots[bestIndex] = nextSlot
        }
        return ReconcileResult(
            iseScores = (0 until n).map { iseForScoreSlot(slots[it], fullPoints[it]) },
            awardedScores = (0 until n).map { awardedForScoreSlot(slots[it], fullPoints[it]) },
            total = roundPoints(total),
            steps = steps,
        )
    }

    /**
     * 逐题 sync-v2 的 `score_detail`（移动端默认口径，8 键）：
     * `{total_score, fluency_score, accuracy_score, integrity_score, standard_score,
     * category, rate_scale}`，选项类额外带 `choose` / `choose_id`。
     */
    fun buildScoreDetail(
        ise: Double,
        category: String,
        choose: String = "",
        chooseId: String = "",
    ): String {
        val total = ise.coerceIn(0.0, 5.0)
        val payload = org.json.JSONObject()
            .put("total_score", format2(total))
            .put("fluency_score", format2(min(5.0, total * 0.98)))
            .put("accuracy_score", format2(min(5.0, total * 0.99)))
            .put("integrity_score", format2(min(5.0, total * 0.99)))
            .put("standard_score", format2(min(5.0, total * 0.97)))
            .put("category", category)
            .put("rate_scale", 1)
        if (choose.isNotEmpty()) payload.put("choose", choose)
        if (chooseId.isNotEmpty()) payload.put("choose_id", chooseId)
        return payload.toString()
    }

    private fun format2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** `_classify_question_type`：返回（是否口语题，是否客观题）。 */
    fun classifyQuestionType(
        category: String,
        typeName: String,
        options: List<String>,
    ): Pair<Boolean, Boolean> {
        val cat = category.lowercase()
        val type = typeName.lowercase()
        val isOral = cat in ORAL_CATEGORIES || ORAL_KEYWORDS.any { it in type }
        val isChoose = cat in CHOICE_CATEGORIES || CHOICE_KEYWORDS.any { it in type } || options.isNotEmpty()
        return isOral to isChoose
    }

    /** Σ 官方记账贡献（两位小数）—— 官方会记在账上的总分。 */
    fun officialLedgerTotal(iseScores: List<Double>, points: List<Double>): Double {
        var total = 0.0
        for (index in points.indices) {
            total += officialContribution(iseScores.getOrElse(index) { 0.0 }, points[index])
        }
        return roundPoints(total)
    }

    data class TargetScores(val ise: List<Double>, val awarded: List<Double>)

    /**
     * `calc_target_ise_scores_by_full`：把目标总分摊到各题并反推 ISE。
     *
     * 客观题（[allOrNothing]）只给 0 / 满分；零头由主观题连续档位吸收。
     */
    fun calcTargetIseScoresByFull(
        targetRatePercent: Double,
        fullPoints: List<Double>,
        allOrNothing: List<Boolean>,
        seed: Long,
    ): TargetScores {
        val n = fullPoints.size
        if (n <= 0) return TargetScores(emptyList(), emptyList())
        val rate = targetRatePercent / 100.0
        val totalFull = fullPoints.sumOf { max(0.0, it) }
        val maxSlots = fullPoints.map { ceil(max(0.0, it) / GRADUATION).toInt() }
        if (totalFull <= 0) return TargetScores(List(n) { 5.0 }, List(n) { 0.0 })
        val targetTotal = totalFull * rate
        val flags = allOrNothing.toMutableList()
        while (flags.size < n) flags.add(false)
        val binaryIndices = (0 until n).filter { flags[it] && fullPoints[it] > 0.0 }
        val contIndices = (0 until n).filter { it !in binaryIndices }
        if (binaryIndices.isEmpty()) {
            return continuousScores(
                fullPoints = fullPoints,
                maxSlots = maxSlots,
                targetTotal = targetTotal,
                targetRatePercent = targetRatePercent,
                rng = Random(seed),
            )
        }

        val rng = Random(seed)
        val iseScores = MutableList(n) { 0.0 }
        val awardedScores = MutableList(n) { 0.0 }
        val contFull = contIndices.sumOf { max(0.0, fullPoints[it]) }
        val binaryFloor = max(0.0, targetTotal - contFull)

        val order = binaryIndices
            .map { index -> index to max(0.0, fullPoints[index]).pow(0.9) * (0.5 + rng.nextDouble()) }
            .sortedByDescending { it.second }
            .map { it.first }
        val binaryFull = mutableSetOf<Int>()
        var binaryAwarded = 0.0
        for (index in order) {
            val point = max(0.0, fullPoints[index])
            if (binaryAwarded + point <= targetTotal + 1e-9) {
                binaryFull.add(index)
                binaryAwarded += point
            }
        }
        if (binaryAwarded < binaryFloor - 1e-9) {
            val rest = binaryIndices.filter { it !in binaryFull }.sortedBy { max(0.0, fullPoints[it]) }
            for (index in rest) {
                if (binaryAwarded >= binaryFloor - 1e-9) break
                val point = max(0.0, fullPoints[index])
                if (binaryAwarded + point <= targetTotal + 1e-9) {
                    binaryFull.add(index)
                    binaryAwarded += point
                } else {
                    if (kotlin.math.abs(binaryAwarded + point - targetTotal) <=
                        kotlin.math.abs(binaryAwarded - targetTotal)
                    ) {
                        binaryFull.add(index)
                        binaryAwarded += point
                    }
                    break
                }
            }
        }
        binaryAwarded = binaryFull.sumOf { max(0.0, fullPoints[it]) }

        for (index in binaryIndices) {
            val point = max(0.0, fullPoints[index])
            if (index in binaryFull) {
                iseScores[index] = 5.0
                awardedScores[index] = min(point, roundPoints(point))
            } else {
                iseScores[index] = 0.0
                awardedScores[index] = 0.0
            }
        }

        if (contIndices.isNotEmpty()) {
            val contPoints = contIndices.map { fullPoints[it] }
            val contSlots = contIndices.map { maxSlots[it] }
            val contTarget = max(0.0, min(contFull, targetTotal - binaryAwarded))
            val cont = continuousScores(contPoints, contSlots, contTarget, targetRatePercent, rng)
            for ((position, index) in contIndices.withIndex()) {
                iseScores[index] = cont.ise.getOrElse(position) { 5.0 }
                awardedScores[index] = cont.awarded.getOrElse(position) { 0.0 }
            }
        }
        return TargetScores(iseScores.toList(), awardedScores.toList())
    }

    /** `_continuous_scores`：主观题连续档位分配（返回与分配器反推逐字一致的 ISE）。 */
    private fun continuousScores(
        fullPoints: List<Double>,
        maxSlots: List<Int>,
        targetTotal: Double,
        targetRatePercent: Double,
        rng: Random,
    ): TargetScores {
        val totalMaxSlots = maxSlots.sum()
        var targetSlots = round(targetTotal / GRADUATION).toInt()
        targetSlots = max(0, min(totalMaxSlots, targetSlots))
        val loss = allocateLossSlots(
            fullPoints = fullPoints,
            lossSlots = totalMaxSlots - targetSlots,
            maxSlots = maxSlots,
            targetRate = targetRatePercent,
            rng = rng,
        )
        val slots = maxSlots.indices.map { maxSlots[it] - loss[it] }.toMutableList()

        fun awardFor(index: Int, slotCount: Int): Double {
            val full = max(0.0, fullPoints[index])
            if (full <= 0.0) return 0.0
            return min(full, roundPoints(slotCount * GRADUATION))
        }

        val awardedScores = slots.indices.map { awardFor(it, slots[it]) }.toMutableList()

        val n = fullPoints.size
        for (step in 0 until 4 * n + 8) {
            val gap = targetTotal - awardedScores.sum()
            if (kotlin.math.abs(gap) <= 1e-9) break
            if (gap > 0) {
                var bestIndex = -1
                var bestGain = 0.0
                for (index in 0 until n) {
                    if (slots[index] >= maxSlots[index]) continue
                    val gain = awardFor(index, slots[index] + 1) - awardedScores[index]
                    if (gain <= 1e-9 || gain > gap + 1e-9) continue
                    if (gain > bestGain) {
                        bestIndex = index
                        bestGain = gain
                    }
                }
                if (bestIndex < 0) break
                slots[bestIndex] += 1
                awardedScores[bestIndex] = awardFor(bestIndex, slots[bestIndex])
            } else {
                val candidates = mutableListOf<Int>()
                for (index in 0 until n) {
                    if (slots[index] <= 0) continue
                    val drop = awardedScores[index] - awardFor(index, slots[index] - 1)
                    if (drop <= 1e-9 || drop > -gap + 1e-9) continue
                    candidates.add(index)
                }
                if (candidates.isEmpty()) break
                val chosen = candidates[rng.nextInt(candidates.size)]
                slots[chosen] -= 1
                awardedScores[chosen] = awardFor(chosen, slots[chosen])
            }
        }

        return TargetScores(
            ise = slots.indices.map { iseForScoreSlot(slots[it], fullPoints[it]) },
            awarded = awardedScores.toList(),
        )
    }

    /** 按题型/文本长度给每题分配作答用时，总和等于 [totalSeconds]。 */
    fun allocateUseTimes(categories: List<String>, texts: List<String>, totalSeconds: Int): List<Int> {
        val n = categories.size
        if (n == 0) return emptyList()
        val total = if (totalSeconds <= 0) n * 15 else totalSeconds
        val weights = (0 until n).map { index ->
            val category = categories[index].lowercase()
            val words = texts[index].trim().split(Regex("\\s+")).count { it.isNotBlank() }
            when {
                "choose" in category -> 8.0
                "repeat_essay" in category || "picture" in category || words > 30 -> 30.0
                "role" in category || "dialogue" in category || words > 15 -> 20.0
                else -> 15.0
            }
        }
        val totalWeight = weights.sum().takeIf { it > 0 } ?: n.toDouble()
        val times = weights.map { max(3, round(total * it / totalWeight).toInt()) }.toMutableList()
        times[times.lastIndex] += total - times.sum()
        if (times.last() < 1) {
            return times.map { max(1, it) }
        }
        return times
    }
}
