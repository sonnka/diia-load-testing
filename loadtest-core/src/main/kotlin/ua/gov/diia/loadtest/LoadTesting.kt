package ua.gov.diia.loadtest

import kotlinx.coroutines.runInterruptible
import java.util.function.Consumer
import kotlin.time.toKotlinDuration

/** Body of a Java scenario: what one run does. */
public fun interface ScenarioBody {
    @Throws(Exception::class)
    public fun run(run: Run)
}

/** A measured step that produces a value. */
public fun interface StepCall<T> {
    @Throws(Exception::class)
    public fun call(): T
}

/** A measured step without a result. */
public fun interface StepAction {
    @Throws(Exception::class)
    public fun run()
}

/** One run of a Java scenario: the Java counterpart of [RunScope]. */
public class Run internal constructor(
    private val scope: RunScope,
) {
    /** 1-based sequence number of this run; handy for deriving a distinct per-run identity. */
    public val runId: Long get() = scope.runId

    /** Profile phase this run was started in. */
    public val phase: String get() = scope.phase

    /**
     * Times [call] and records it under [name]; returns its result. If it throws, the failure is
     * recorded and the rest of this run is skipped (see [RunScope.step]).
     */
    public fun <T> step(
        name: String,
        call: StepCall<T>,
    ): T = scope.stepBlocking(name) { call.call() }

    /** [step] for calls without a result. */
    public fun step(
        name: String,
        action: StepAction,
    ) {
        scope.stepBlocking(name) { action.run() }
    }
}

/** Java entry points. Kotlin code uses [scenario], [runLoadTest], [assertions] and [finish] directly. */
public object LoadTesting {
    /** A scenario whose [body] is blocking Java code; the engine runs each run of it on its own virtual thread. */
    @JvmStatic
    public fun scenario(
        name: String,
        body: ScenarioBody,
    ): Scenario = Scenario(name, { runInterruptible { body.run(Run(this)) } }, blocking = true)

    /** Runs [scenario] under [profile] and returns everything recorded. */
    @JvmStatic
    @JvmOverloads
    public fun run(
        scenario: Scenario,
        profile: LoadProfile,
        options: RunOptionsSpec = RunOptionsSpec(),
    ): LoadTestResult = runLoadTest(scenario, profile, options.build())

    @JvmStatic
    public fun options(): RunOptionsSpec = RunOptionsSpec()

    /** A custom profile; [ProfileSettings.fromSystemProperties] covers the standard steady/stress shapes. */
    @JvmStatic
    public fun profile(): ProfileSpec = ProfileSpec()

    @JvmStatic
    public fun assertions(): AssertionsSpec = AssertionsSpec()

    /** See [LoadTestResult.finish]. */
    @JvmStatic
    @JvmOverloads
    public fun finish(
        result: LoadTestResult,
        title: String = result.scenario,
        verdict: Verdict? = null,
    ): ReportFiles = result.finish(title, verdict)
}

/** Java builder for [RunOptions]. */
public class RunOptionsSpec internal constructor() {
    private var options = RunOptions()

    public fun maxInFlight(maxInFlight: Int): RunOptionsSpec = apply { options = options.copy(maxInFlight = maxInFlight) }

    public fun drainTimeout(timeout: java.time.Duration): RunOptionsSpec =
        apply { options = options.copy(drainTimeout = timeout.toKotlinDuration()) }

    public fun progressInterval(interval: java.time.Duration): RunOptionsSpec =
        apply { options = options.copy(progressInterval = interval.toKotlinDuration()) }

    public fun failureClassifier(classifier: FailureClassifier): RunOptionsSpec =
        apply { options = options.copy(failureClassifier = classifier) }

    public fun onProgress(listener: Consumer<Progress>): RunOptionsSpec = apply { options = options.copy(onProgress = listener::accept) }

    internal fun build(): RunOptions = options
}

/** Java builder for a [LoadProfile]. */
public class ProfileSpec internal constructor() {
    private val builder = LoadProfileBuilder()

    @JvmOverloads
    public fun constant(
        rate: Double,
        duration: java.time.Duration,
        phase: String = Phases.STEADY,
    ): ProfileSpec = apply { builder.constant(rate, duration.toKotlinDuration(), phase) }

    @JvmOverloads
    public fun ramp(
        from: Double,
        to: Double,
        duration: java.time.Duration,
        phase: String = Phases.RAMP,
    ): ProfileSpec = apply { builder.ramp(from, to, duration.toKotlinDuration(), phase) }

    public fun build(): LoadProfile = builder.build()
}

/** Java builder for [Assertions]; same semantics as the Kotlin [assertions] DSL. */
public class AssertionsSpec internal constructor() {
    private val rules = mutableListOf<Rule>()

    public fun phase(
        name: String,
        scope: Consumer<ScopeSpec>,
    ): AssertionsSpec = apply { scope.accept(ScopeSpec(name, rules)) }

    public fun allPhases(scope: Consumer<ScopeSpec>): AssertionsSpec = apply { scope.accept(ScopeSpec(null, rules)) }

    /** Fails if any arrival was dropped (max in-flight reached) or any run was abandoned at drain timeout. */
    public fun noDroppedArrivals(): AssertionsSpec = apply { rules += Rule.NoDropped }

    public fun build(): Assertions = Assertions(rules.toList())
}

public class ScopeSpec internal constructor(
    private val phase: String?,
    private val rules: MutableList<Rule>,
) {
    /** Applies the criteria to every step that recorded anything in this scope, separately. */
    public fun eachStep(criteria: Consumer<CriteriaSpec>): ScopeSpec = add(StepSelector.Each.ALL_STEPS, criteria)

    public fun step(
        name: String,
        criteria: Consumer<CriteriaSpec>,
    ): ScopeSpec = add(StepSelector.Named(name), criteria)

    /** Applies the criteria to all steps merged together. */
    public fun allSteps(criteria: Consumer<CriteriaSpec>): ScopeSpec = add(StepSelector.All, criteria)

    /** See [ScopeBuilder.runErrorRateAtMost]. */
    public fun runErrorRateAtMost(rate: Double): ScopeSpec = apply { rules += Rule.RunErrorRate(phase, rate) }

    /** See [ScopeBuilder.runP99Below]. */
    public fun runP99Below(limit: java.time.Duration): ScopeSpec = apply { rules += Rule.RunDuration(phase, limit.toKotlinDuration()) }

    /** See [ScopeBuilder.clientLagP99Below]. */
    public fun clientLagP99Below(limit: java.time.Duration): ScopeSpec = apply { rules += Rule.ClientLag(phase, limit.toKotlinDuration()) }

    private fun add(
        selector: StepSelector,
        criteria: Consumer<CriteriaSpec>,
    ): ScopeSpec =
        apply {
            val spec = CriteriaSpec()
            criteria.accept(spec)
            rules += Rule.Steps(phase, selector, spec.criteria.toList())
        }
}

public class CriteriaSpec internal constructor() {
    internal val criteria = mutableListOf<Criterion>()

    public fun p50Below(limit: java.time.Duration): CriteriaSpec = latency("p50", limit) { it.p50 }

    public fun p95Below(limit: java.time.Duration): CriteriaSpec = latency("p95", limit) { it.p95 }

    public fun p99Below(limit: java.time.Duration): CriteriaSpec = latency("p99", limit) { it.p99 }

    public fun maxBelow(limit: java.time.Duration): CriteriaSpec = latency("max", limit) { it.max }

    public fun errorRateAtMost(rate: Double): CriteriaSpec = apply { criteria += errorRateCriterion(rate) }

    public fun minRequests(count: Long): CriteriaSpec = apply { criteria += minRequestsCriterion(count) }

    private fun latency(
        name: String,
        limit: java.time.Duration,
        pick: (Latencies) -> kotlin.time.Duration,
    ): CriteriaSpec = apply { criteria += latencyCriterion(name, limit.toKotlinDuration(), pick) }
}
