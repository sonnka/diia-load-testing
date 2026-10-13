package ua.gov.diia.loadtest

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

public data class RunOptions(
    /**
     * Upper bound on concurrently running scenario runs. Arrivals beyond it are dropped and counted
     * (see [LoadTestResult.dropped]) instead of piling up until the load generator runs out of memory.
     */
    public val maxInFlight: Int = 10_000,
    /** How long to wait for in-flight runs once the profile is over before cancelling them. */
    public val drainTimeout: Duration = 30.seconds,
    /** How often [onProgress] is called; whole seconds. */
    public val progressInterval: Duration = 5.seconds,
    public val failureClassifier: FailureClassifier = FailureClassifier.Default,
    /**
     * Where scenario runs execute; null (the default) picks [Dispatchers.Default] for suspending scenarios
     * and one virtual thread per run for blocking ones ([LoadTesting.scenario]). If set, keep it multi-threaded:
     * request preparation (crypto etc.) is CPU work.
     */
    public val dispatcher: CoroutineDispatcher? = null,
    public val onProgress: (Progress) -> Unit = ConsoleReport::printProgress,
) {
    init {
        require(maxInFlight > 0) { "maxInFlight must be > 0" }
    }
}

/** Live view of the last [RunOptions.progressInterval]. */
public data class Progress(
    public val elapsed: Duration,
    public val phase: String,
    public val targetRate: Double,
    public val arrivalsPerSecond: Double,
    public val okPerSecond: Double,
    public val koPerSecond: Double,
    public val p99: Duration,
    public val inFlight: Int,
    public val droppedTotal: Long,
)

public class LoadTestResult internal constructor(
    public val scenario: String,
    public val profile: LoadProfile,
    public val startedAt: Instant,
    public val elapsed: Duration,
    /** Step names in the order they were first recorded. */
    public val steps: List<String>,
    private val stats: Map<StatsKey, StepStats>,
    public val timeline: List<TimelinePoint>,
    private val clientLag: Map<String, Latencies>,
    /** Arrivals not started because [RunOptions.maxInFlight] was reached, by phase. */
    public val dropped: Map<String, Long>,
    private val runs: Map<String, Long>,
    private val failedRuns: Map<String, Long>,
    private val runDurations: Map<String, Latencies>,
    /** Runs still in flight when [RunOptions.drainTimeout] ran out, and cancelled. */
    public val abandoned: Long,
) {
    public val phases: List<String> = profile.phases.filter { phase -> stats.keys.any { it.phase == phase } }

    public val totalDropped: Long get() = dropped.values.sum()

    /** Runs that finished in [phase] (all phases when null); abandoned runs are not counted. */
    @JvmOverloads
    public fun runs(phase: String? = null): Long = if (phase == null) runs.values.sum() else runs[phase] ?: 0L

    /**
     * How long whole runs took, start to end, successful or not: the scenario as one user experiences it,
     * including the preparation between steps.
     */
    @JvmOverloads
    public fun runDuration(phase: String? = null): Latencies {
        val histogram = newHistogram()
        runDurations.filterKeys { phase == null || it == phase }.values.forEach { histogram.add(it.histogram) }
        return Latencies(histogram)
    }

    /** Finished runs in which a step, or the code outside steps, failed. */
    @JvmOverloads
    public fun failedRuns(phase: String? = null): Long = if (phase == null) failedRuns.values.sum() else failedRuns[phase] ?: 0L

    /** Stats for one phase/step, or merged across all of them when null. */
    public fun stats(
        phase: String? = null,
        step: String? = null,
    ): StepStats =
        StepStats.merge(
            stats.filterKeys { (p, s) -> (phase == null || p == phase) && (step == null || s == step) }.values,
        )

    /** Delay between a run's scheduled and actual start; growing values mean the load generator itself is saturated. */
    public fun clientLag(phase: String? = null): Latencies {
        val histogram = newHistogram()
        clientLag.filterKeys { phase == null || it == phase }.values.forEach { histogram.add(it.histogram) }
        return Latencies(histogram)
    }
}

/**
 * Runs [scenario] once per arrival of the open-workload [profile] and returns everything recorded.
 *
 * The arrival scheduler and the 1-second metrics ticker run in the caller's context; the runs
 * themselves on [RunOptions.dispatcher]. Each arrival is started at its own scheduled instant (not in
 * batches) and independently of how earlier runs are doing - a slow server sees more concurrent
 * runs, never fewer arrivals, so its latency is not hidden by the load generator backing off.
 */
public suspend fun executeLoadTest(
    scenario: Scenario,
    profile: LoadProfile,
    options: RunOptions = RunOptions(),
): LoadTestResult {
    if (options.dispatcher != null || !scenario.blocking) {
        return execute(scenario, profile, options, options.dispatcher ?: Dispatchers.Default)
    }
    return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        execute(scenario, profile, options, executor.asCoroutineDispatcher())
    }
}

private suspend fun execute(
    scenario: Scenario,
    profile: LoadProfile,
    options: RunOptions,
    dispatcher: CoroutineDispatcher,
): LoadTestResult =
    coroutineScope {
        val metrics = MetricsCollector()
        val inFlight = AtomicInteger()
        val launched = AtomicLong()
        val tickedSeconds = AtomicInteger()
        val startedAt = Instant.now()
        val start = TimeSource.Monotonic.markNow()

        fun elapsedSeconds(): Double = start.elapsedNow().toDouble(DurationUnit.SECONDS)

        val ticker =
            launch {
                val progressEvery =
                    options.progressInterval.inWholeSeconds
                        .toInt()
                        .coerceAtLeast(1)
                var launchedAtLastProgress = 0L
                while (true) {
                    val second = tickedSeconds.get()
                    delay(((second + 1 - elapsedSeconds()) * 1_000).toLong().coerceAtLeast(0))
                    metrics.tick(second)
                    tickedSeconds.incrementAndGet()
                    if ((second + 1) % progressEvery == 0) {
                        val now = elapsedSeconds()
                        val window = metrics.takeWindow()
                        val launchedNow = launched.get()
                        options.onProgress(
                            Progress(
                                elapsed = start.elapsedNow(),
                                phase = profile.phaseAt(now),
                                targetRate = profile.rateAt(now),
                                arrivalsPerSecond = (launchedNow - launchedAtLastProgress).toDouble() / progressEvery,
                                okPerSecond = window.ok.toDouble() / progressEvery,
                                koPerSecond = window.ko.toDouble() / progressEvery,
                                p99 = window.p99,
                                inFlight = inFlight.get(),
                                droppedTotal = metrics.droppedTotal(),
                            ),
                        )
                        launchedAtLastProgress = launchedNow
                    }
                }
            }

        val runs = SupervisorJob(coroutineContext.job)
        val runContext = runs + dispatcher

        while (true) {
            val n = launched.get() + 1
            val due = profile.dueTimeOf(n) ?: break
            val wait = due - elapsedSeconds()
            if (wait > 0.001) {
                delay((wait * 1_000).toLong())
                continue
            }
            launched.set(n)
            val phase = profile.phaseAt(due)
            if (inFlight.get() >= options.maxInFlight) {
                metrics.recordDropped(phase)
                continue
            }
            inFlight.incrementAndGet()
            launch(runContext) {
                try {
                    metrics.recordLag(phase, ((elapsedSeconds() - due) * 1e9).toLong())
                    runOnce(scenario, RunScope(n, phase, metrics, options.failureClassifier), metrics, options.failureClassifier)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        runs.complete()
        val drained = withTimeoutOrNull(options.drainTimeout) { runs.join() } != null
        val abandoned = if (drained) 0L else inFlight.get().toLong()
        if (!drained) runs.cancelAndJoin()

        ticker.cancelAndJoin()
        metrics.tick(tickedSeconds.get())

        metrics.result(scenario.name, profile, startedAt, start.elapsedNow(), abandoned)
    }

/** Blocking entry point for a `main`. */
public fun runLoadTest(
    scenario: Scenario,
    profile: LoadProfile,
    options: RunOptions = RunOptions(),
): LoadTestResult = runBlocking { executeLoadTest(scenario, profile, options) }
