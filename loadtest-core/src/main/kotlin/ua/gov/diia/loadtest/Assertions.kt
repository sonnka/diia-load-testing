package ua.gov.diia.loadtest

import kotlin.time.Duration

/**
 * Pass/fail criteria evaluated against a [LoadTestResult], typically restricted to the steady phase
 * so warm-up (JIT, connection pools) cannot fail a run or hide a regression
 */
public class Assertions internal constructor(
    private val rules: List<Rule>,
) {
    public fun verify(result: LoadTestResult): Verdict = Verdict(rules.flatMap { it.evaluate(result) })
}

public fun assertions(block: AssertionsBuilder.() -> Unit): Assertions = Assertions(AssertionsBuilder().apply(block).rules.toList())

public class AssertionResult internal constructor(
    public val description: String,
    public val passed: Boolean,
    public val actual: String,
) {
    override fun toString(): String = "${if (passed) "PASS" else "FAIL"}  $description  (actual: $actual)"
}

public class Verdict internal constructor(
    public val results: List<AssertionResult>,
) {
    public val passed: Boolean get() = results.all { it.passed }
}

public class AssertionsBuilder internal constructor() {
    internal val rules = mutableListOf<Rule>()

    public fun phase(
        name: String,
        block: ScopeBuilder.() -> Unit,
    ) {
        ScopeBuilder(name, rules).block()
    }

    public fun allPhases(block: ScopeBuilder.() -> Unit) {
        ScopeBuilder(null, rules).block()
    }

    /** Fails if any arrival was dropped (max in-flight reached) or any run was abandoned at drain timeout. */
    public fun noDroppedArrivals() {
        rules += Rule.NoDropped
    }
}

public class ScopeBuilder internal constructor(
    private val phase: String?,
    private val rules: MutableList<Rule>,
) {
    /** Applies the criteria to every step that recorded anything in this scope, separately. */
    public fun eachStep(block: CriteriaBuilder.() -> Unit) {
        rules += Rule.Steps(phase, StepSelector.Each.ALL_STEPS, CriteriaBuilder().apply(block).criteria)
    }

    public fun step(
        name: String,
        block: CriteriaBuilder.() -> Unit,
    ) {
        rules += Rule.Steps(phase, StepSelector.Named(name), CriteriaBuilder().apply(block).criteria)
    }

    /** Applies the criteria to all steps merged together. */
    public fun allSteps(block: CriteriaBuilder.() -> Unit) {
        rules += Rule.Steps(phase, StepSelector.All, CriteriaBuilder().apply(block).criteria)
    }

    /** Fails if more than [rate] of the finished runs (whole scenario executions) failed. */
    public fun runErrorRateAtMost(rate: Double) {
        rules += Rule.RunErrorRate(phase, rate)
    }

    /**
     * Fails if the p99 of client start lag reaches [limit]: past that the load generator itself is
     * saturated, its latency numbers are not the server's, and the run proves nothing either way.
     */
    public fun clientLagP99Below(limit: Duration) {
        rules += Rule.ClientLag(phase, limit)
    }

    /** Fails if the p99 of whole runs (scenario start to end, successful or not) reaches [limit]. */
    public fun runP99Below(limit: Duration) {
        rules += Rule.RunDuration(phase, limit)
    }
}

public class CriteriaBuilder internal constructor() {
    internal val criteria = mutableListOf<Criterion>()

    public fun p50Below(limit: Duration) {
        criteria += latencyCriterion("p50", limit) { it.p50 }
    }

    public fun p95Below(limit: Duration) {
        criteria += latencyCriterion("p95", limit) { it.p95 }
    }

    public fun p99Below(limit: Duration) {
        criteria += latencyCriterion("p99", limit) { it.p99 }
    }

    public fun maxBelow(limit: Duration) {
        criteria += latencyCriterion("max", limit) { it.max }
    }

    public fun errorRateAtMost(rate: Double) {
        criteria += errorRateCriterion(rate)
    }

    public fun minRequests(count: Long) {
        criteria += minRequestsCriterion(count)
    }
}

internal fun latencyCriterion(
    name: String,
    limit: Duration,
    pick: (Latencies) -> Duration,
): Criterion = Criterion("$name < ${limit.formatMillis()}ms", { pick(it.latency).formatMillis() + "ms" }) { pick(it.latency) < limit }

internal fun errorRateCriterion(rate: Double): Criterion =
    Criterion("error rate <= ${percent(rate)}", { percent(it.errorRate) }) { it.errorRate <= rate }

internal fun minRequestsCriterion(count: Long): Criterion = Criterion("requests >= $count", { it.total.toString() }) { it.total >= count }

internal class Criterion(
    val description: String,
    val actual: (StepStats) -> String,
    val check: (StepStats) -> Boolean,
)

internal sealed interface StepSelector {
    data class Each(
        val except: Set<String> = emptySet(),
    ) : StepSelector {
        companion object {
            val ALL_STEPS = Each()
        }
    }

    data class IfRecorded(
        val name: String,
    ) : StepSelector

    data object All : StepSelector

    data class Named(
        val name: String,
    ) : StepSelector
}

internal sealed interface Rule {
    fun evaluate(result: LoadTestResult): List<AssertionResult>

    data object NoDropped : Rule {
        override fun evaluate(result: LoadTestResult): List<AssertionResult> =
            listOf(
                AssertionResult(
                    description = "no dropped or abandoned runs",
                    passed = result.totalDropped == 0L && result.abandoned == 0L,
                    actual = "dropped=${result.totalDropped}, abandoned=${result.abandoned}",
                ),
            )
    }

    class RunErrorRate(
        private val phase: String?,
        private val rate: Double,
    ) : Rule {
        override fun evaluate(result: LoadTestResult): List<AssertionResult> {
            val runs = result.runs(phase)
            val failed = result.failedRuns(phase)
            val actual = if (runs == 0L) 0.0 else failed.toDouble() / runs
            return listOf(
                AssertionResult(
                    description = "[${phase ?: "all phases"}] failed runs <= ${percent(rate)}",
                    passed = runs > 0 && actual <= rate,
                    actual = if (runs == 0L) "no runs finished" else "${percent(actual)} ($failed of $runs)",
                ),
            )
        }
    }

    class RunDuration(
        private val phase: String?,
        private val limit: Duration,
    ) : Rule {
        override fun evaluate(result: LoadTestResult): List<AssertionResult> {
            val duration = result.runDuration(phase)
            return listOf(
                AssertionResult(
                    description = "[${phase ?: "all phases"}] whole run p99 < ${limit.formatMillis()}ms",
                    passed = duration.count > 0 && duration.p99 < limit,
                    actual = if (duration.count == 0L) "no runs finished" else duration.p99.formatMillis() + "ms",
                ),
            )
        }
    }

    class ClientLag(
        private val phase: String?,
        private val limit: Duration,
    ) : Rule {
        override fun evaluate(result: LoadTestResult): List<AssertionResult> {
            val p99 = result.clientLag(phase).p99
            return listOf(
                AssertionResult(
                    description = "[${phase ?: "all phases"}] client start lag p99 < ${limit.formatMillis()}ms",
                    passed = p99 < limit,
                    actual = p99.formatMillis() + "ms",
                ),
            )
        }
    }

    class Steps(
        private val phase: String?,
        private val selector: StepSelector,
        private val criteria: List<Criterion>,
    ) : Rule {
        override fun evaluate(result: LoadTestResult): List<AssertionResult> {
            val scope = "[${phase ?: "all phases"}]"
            val targets =
                when (selector) {
                    StepSelector.All -> listOf("all steps" to result.stats(phase))
                    is StepSelector.Each -> recorded(result, phase) { it !in selector.except }
                    is StepSelector.IfRecorded -> recorded(result, phase) { it == selector.name }
                    is StepSelector.Named -> listOf(selector.name to result.stats(phase, selector.name))
                }
            if (targets.isEmpty() && selector is StepSelector.IfRecorded) return emptyList()
            if (targets.isEmpty()) return listOf(AssertionResult("$scope each step", false, "no requests recorded"))
            return targets.flatMap { (name, stats) ->
                if (stats.total == 0L) {
                    listOf(AssertionResult("$scope $name", false, "no requests recorded"))
                } else {
                    criteria.map { AssertionResult("$scope $name: ${it.description}", it.check(stats), it.actual(stats)) }
                }
            }
        }

        private fun recorded(
            result: LoadTestResult,
            phase: String?,
            include: (String) -> Boolean,
        ): List<Pair<String, StepStats>> =
            result.steps
                .filter(include)
                .map { it to result.stats(phase, it) }
                .filter { it.second.total > 0 }
    }
}
