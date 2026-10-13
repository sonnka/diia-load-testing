package ua.gov.diia.loadtest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Pseudo step name under which failures thrown outside any [RunScope.step] are reported. */
public const val OUTSIDE_STEPS: String = "(outside steps)"

/**
 * What one simulated arrival does. The body is plain suspending code: only the blocks wrapped in
 * [RunScope.step] are timed and reported, so preparing a request (generating keys, signing a proof)
 * stays out of the measured latency, and values flow between steps as ordinary typed `val`s.
 */
public class Scenario internal constructor(
    public val name: String,
    internal val body: suspend RunScope.() -> Unit,
    /** Blocking (Java) bodies run on virtual threads instead of the coroutine dispatcher. */
    internal val blocking: Boolean,
) {
    public constructor(name: String, body: suspend RunScope.() -> Unit) : this(name, body, blocking = false)
}

public fun scenario(
    name: String,
    body: suspend RunScope.() -> Unit,
): Scenario = Scenario(name, body)

/** Turns a step's failure into a short code for reports, e.g. a gRPC status code name. */
public fun interface FailureClassifier {
    public fun classify(error: Throwable): String

    public companion object {
        @JvmField
        public val Default: FailureClassifier = FailureClassifier { it::class.simpleName ?: "Unknown" }
    }
}

/** Receiver of a [Scenario] body for one run. */
public class RunScope internal constructor(
    /** 1-based sequence number of this run; handy for deriving a distinct per-run identity. */
    public val runId: Long,
    /** Profile phase this run was started in; all of its steps are reported under it. */
    public val phase: String,
    private val metrics: MetricsCollector,
    private val classifier: FailureClassifier,
) {
    /**
     * Times [block] and records it under [name]. If it throws, the failure is recorded and the rest
     * of this run is skipped: later steps would only fail for lack of what this one should have
     * produced, so they are not counted as failures of their own.
     */
    public suspend fun <T> step(
        name: String,
        block: suspend () -> T,
    ): T = record(name) { block() }

    /** [step] for blocking code - what the Java API ([LoadTesting.scenario]) runs on virtual threads. */
    internal fun <T> stepBlocking(
        name: String,
        block: () -> T,
    ): T = record(name, block)

    private inline fun <T> record(
        name: String,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        val value =
            try {
                block()
            } catch (e: StepFailed) {
                throw e // a nested step already recorded it
            } catch (e: Throwable) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val code = classifier.classify(e)
                metrics.recordFailure(phase, name, code, describeFailure(e, code), System.nanoTime() - start)
                throw StepFailed(name, e)
            }
        metrics.recordSuccess(phase, name, System.nanoTime() - start)
        return value
    }
}

/** Aborts the current run after a step failure was recorded. Carries no stack trace: it is control flow. */
internal class StepFailed(
    step: String,
    cause: Throwable,
) : RuntimeException("Step '$step' failed", cause, false, false)

internal suspend fun runOnce(
    scenario: Scenario,
    scope: RunScope,
    metrics: MetricsCollector,
    classifier: FailureClassifier,
) {
    val start = System.nanoTime()
    val failed =
        try {
            scenario.body(scope)
            false
        } catch (e: StepFailed) {
            true // already recorded by the step that failed
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val code = classifier.classify(e)
            metrics.recordFailure(scope.phase, OUTSIDE_STEPS, code, describeFailure(e, code), null)
            true
        }
    metrics.recordRun(scope.phase, failed, System.nanoTime() - start)
}
