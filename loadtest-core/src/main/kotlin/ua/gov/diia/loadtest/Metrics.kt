package ua.gov.diia.loadtest

import org.HdrHistogram.Histogram
import org.HdrHistogram.Recorder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

private const val SIGNIFICANT_DIGITS = 3

/** Auto-resizing, microsecond values. */
internal fun newHistogram(): Histogram = Histogram(SIGNIFICANT_DIGITS)

/** A latency distribution at microsecond resolution. */
public class Latencies internal constructor(
    internal val histogram: Histogram,
) {
    public val count: Long get() = histogram.totalCount

    public fun percentile(percentile: Double): Duration =
        if (count == 0L) Duration.ZERO else histogram.getValueAtPercentile(percentile).microseconds

    public val p50: Duration get() = percentile(50.0)
    public val p95: Duration get() = percentile(95.0)
    public val p99: Duration get() = percentile(99.0)
    public val max: Duration get() = if (count == 0L) Duration.ZERO else histogram.maxValue.microseconds
    public val mean: Duration get() = if (count == 0L) Duration.ZERO else histogram.mean.microseconds

    /** [percentile] in milliseconds; for Java, where [Duration] is not usable. */
    public fun percentileMillis(percentile: Double): Double = percentile(percentile).millis()

    /** [max] in milliseconds; for Java, where [Duration] is not usable. */
    public val maxMillis: Double get() = max.millis()
}

/** How many failed calls had this [code] and [message] - e.g. `UNAVAILABLE`, `io exception <- Connection reset`. */
public data class FailureDetail(
    public val code: String,
    public val message: String,
    public val count: Long,
)

/** Outcome counts and latencies for one step (or a merge of several), in one phase (or all). */
public class StepStats internal constructor(
    public val ok: Long,
    public val ko: Long,
    /** Failure counts by [FailureClassifier] code. */
    public val failures: Map<String, Long>,
    /**
     * The failures by code and message, most frequent first. Up to [MAX_FAILURE_MESSAGES] distinct messages
     * are kept per step and phase; the rest are counted under [OTHER_MESSAGES].
     */
    public val failureDetails: List<FailureDetail>,
    /** Latencies of the successful calls. */
    public val successLatency: Latencies,
    /**
     * Latencies of the failed calls - e.g. a `DEADLINE_EXCEEDED` after the full deadline. Failures outside
     * any step (preparation code) are not calls and have none.
     */
    public val failureLatency: Latencies,
) {
    /**
     * Latencies of every completed call, successful or failed: what percentiles, assertions and reports
     * use, so slow failures cannot make p99 look better than what callers experienced.
     */
    public val latency: Latencies =
        Latencies(newHistogram().apply { add(successLatency.histogram) }.apply { add(failureLatency.histogram) })

    public val total: Long get() = ok + ko
    public val errorRate: Double get() = if (total == 0L) 0.0 else ko.toDouble() / total

    internal companion object {
        fun merge(parts: Collection<StepStats>): StepStats {
            val success = newHistogram()
            val failure = newHistogram()
            val failures = sortedMapOf<String, Long>()
            val details = HashMap<Pair<String, String>, Long>()
            parts.forEach { part ->
                success.add(part.successLatency.histogram)
                failure.add(part.failureLatency.histogram)
                part.failures.forEach { (code, count) -> failures.merge(code, count, Long::plus) }
                part.failureDetails.forEach { details.merge(it.code to it.message, it.count, Long::plus) }
            }
            return StepStats(
                parts.sumOf { it.ok },
                parts.sumOf { it.ko },
                failures,
                sortedDetails(details),
                Latencies(success),
                Latencies(failure),
            )
        }

        fun sortedDetails(counts: Map<Pair<String, String>, Long>): List<FailureDetail> =
            counts
                .map { (key, count) -> FailureDetail(key.first, key.second, count) }
                .sortedWith(compareByDescending<FailureDetail> { it.count }.thenBy { it.code }.thenBy { it.message })
    }
}

/** Distinct failure messages kept per step and phase; messages often carry ids, which would make every one distinct. */
public const val MAX_FAILURE_MESSAGES: Int = 10

/** The message under which failures beyond [MAX_FAILURE_MESSAGES] distinct ones are counted. */
public const val OTHER_MESSAGES: String = "(other messages)"

private const val MAX_MESSAGE_LENGTH = 300
private const val MAX_CAUSE_DEPTH = 16

/**
 * A one-line description of [error] for reports: its own message (without a leading "[code]: ", which the
 * report shows anyway; decimal fractions such as timings as `#`) and, when different, the root cause's - for gRPC e.g. `io exception <- Connection
 * reset by peer`, where the status says what failed and the root cause why.
 */
internal fun describeFailure(
    error: Throwable,
    code: String,
): String {
    val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).toList()
    val own =
        error.message
            .oneLine()
            .removePrefix("$code:")
            .trim()
            .takeIf { it.isNotEmpty() && it != code }
    val rootCause = chain.last().takeIf { chain.size > 1 }
    val root = rootCause?.let { cause -> cause.message.oneLine().ifEmpty { cause.javaClass.simpleName } }
    val parts = listOfNotNull(own, root?.takeIf { own == null || it !in own })
    val text = parts.joinToString(" <- ").replace(FRACTION, "#").ifEmpty { "(no message)" }
    return if (text.length <= MAX_MESSAGE_LENGTH) text else text.take(MAX_MESSAGE_LENGTH - 3) + "..."
}

/**
 * Decimal fractions such as timings (gRPC: `deadline exceeded after 4.999997100s`) differ in every message
 * and would split one kind of failure into a row per call; integers, IP addresses and versions are kept.
 */
private val FRACTION = Regex("""(?<![\d.])\d+\.\d+(?![\d.])""")

private fun String?.oneLine(): String = this?.replace(Regex("\\s+"), " ")?.trim() ?: ""

/** One second of the run for one step ([step] = null: all steps together); latencies of all calls completed in it. */
public data class TimelinePoint(
    public val second: Int,
    public val step: String?,
    public val ok: Long,
    public val ko: Long,
    /** Calls with a latency in this second: the successful and the failed ones (not failures outside steps). */
    public val timed: Long,
    public val p50: Duration,
    public val p95: Duration,
    public val p99: Duration,
    public val max: Duration,
)

internal data class StatsKey(
    val phase: String,
    val step: String,
)

internal class Window(
    val ok: Long,
    val ko: Long,
    val p99: Duration,
)

/**
 * Lock-free on the recording side (called from every run concurrently); [tick], [takeWindow] and
 * [result] must only be called from one coroutine at a time - the runner's ticker, then the runner
 * itself once the ticker has stopped.
 */
internal class MetricsCollector {
    private class Live {
        val latency = Recorder(SIGNIFICANT_DIGITS)
        val failedLatency = Recorder(SIGNIFICANT_DIGITS)
        val koInterval = LongAdder()
        val failures = ConcurrentHashMap<String, LongAdder>()
        val messages = ConcurrentHashMap<Pair<String, String>, LongAdder>()
    }

    private class Accumulated {
        val latency = newHistogram()
        val failedLatency = newHistogram()
        var ko = 0L
    }

    private val live = ConcurrentHashMap<StatsKey, Live>()
    private val stepOrder = ConcurrentHashMap<String, Int>()
    private val stepCounter = AtomicInteger()
    private val lag = ConcurrentHashMap<String, Recorder>()
    private val dropped = ConcurrentHashMap<String, LongAdder>()
    private val runs = ConcurrentHashMap<String, LongAdder>()
    private val failedRuns = ConcurrentHashMap<String, LongAdder>()
    private val runDurations = ConcurrentHashMap<String, Recorder>()

    // Ticker-owned state.
    private val accumulated = HashMap<StatsKey, Accumulated>()
    private val lagAccumulated = HashMap<String, Histogram>()
    private val runDurationAccumulated = HashMap<String, Histogram>()
    private val timeline = ArrayList<TimelinePoint>()
    private val window = newHistogram()
    private var windowOk = 0L
    private var windowKo = 0L

    private fun live(
        phase: String,
        step: String,
    ): Live {
        stepOrder.computeIfAbsent(step) { stepCounter.getAndIncrement() }
        return live.computeIfAbsent(StatsKey(phase, step)) { Live() }
    }

    fun recordSuccess(
        phase: String,
        step: String,
        nanos: Long,
    ) {
        live(phase, step).latency.recordValue(toMicros(nanos))
    }

    /** [nanos]: how long the failed call took; null for a failure that was not a call (outside any step). */
    fun recordFailure(
        phase: String,
        step: String,
        code: String,
        message: String,
        nanos: Long?,
    ) {
        val cell = live(phase, step)
        nanos?.let { cell.failedLatency.recordValue(toMicros(it)) }
        cell.koInterval.increment()
        cell.failures.computeIfAbsent(code) { LongAdder() }.increment()
        val key = code to message
        val kept = if (cell.messages.containsKey(key) || cell.messages.size < MAX_FAILURE_MESSAGES) key else code to OTHER_MESSAGES
        cell.messages.computeIfAbsent(kept) { LongAdder() }.increment()
    }

    /** How late a run actually started versus when the profile scheduled it: the client's own saturation. */
    fun recordLag(
        phase: String,
        nanos: Long,
    ) {
        lag.computeIfAbsent(phase) { Recorder(SIGNIFICANT_DIGITS) }.recordValue(toMicros(nanos))
    }

    fun recordDropped(phase: String) {
        dropped.computeIfAbsent(phase) { LongAdder() }.increment()
    }

    /** A finished run, failed if any of its steps (or the code outside them) failed. */
    fun recordRun(
        phase: String,
        failed: Boolean,
        nanos: Long,
    ) {
        runs.computeIfAbsent(phase) { LongAdder() }.increment()
        runDurations.computeIfAbsent(phase) { Recorder(SIGNIFICANT_DIGITS) }.recordValue(toMicros(nanos))
        if (failed) failedRuns.computeIfAbsent(phase) { LongAdder() }.increment()
    }

    fun droppedTotal(): Long = dropped.values.sumOf { it.sum() }

    /** Moves everything recorded since the previous tick into the totals and the timeline, as [second]. */
    fun tick(second: Int) {
        val stepLatency = HashMap<String, Histogram>()
        val stepKo = HashMap<String, Long>()
        val all = newHistogram()
        var allKo = 0L
        val stepOk = HashMap<String, Long>()
        var allOk = 0L
        for ((key, cell) in live) {
            val succeeded = cell.latency.getIntervalHistogram()
            val failed = cell.failedLatency.getIntervalHistogram()
            val ko = cell.koInterval.sumThenReset()
            accumulated.getOrPut(key) { Accumulated() }.let {
                it.latency.add(succeeded)
                it.failedLatency.add(failed)
                it.ko += ko
            }
            stepLatency.getOrPut(key.step) { newHistogram() }.apply {
                add(succeeded)
                add(failed)
            }
            stepOk.merge(key.step, succeeded.totalCount, Long::plus)
            stepKo.merge(key.step, ko, Long::plus)
            all.add(succeeded)
            all.add(failed)
            allOk += succeeded.totalCount
            allKo += ko
        }
        for ((step, histogram) in stepLatency) {
            timeline += point(second, step, histogram, stepOk.getValue(step), stepKo.getValue(step))
        }
        timeline += point(second, null, all, allOk, allKo)
        window.add(all)
        windowOk += allOk
        windowKo += allKo
        for ((phase, recorder) in lag) {
            lagAccumulated.getOrPut(phase) { newHistogram() }.add(recorder.getIntervalHistogram())
        }
        for ((phase, recorder) in runDurations) {
            runDurationAccumulated.getOrPut(phase) { newHistogram() }.add(recorder.getIntervalHistogram())
        }
    }

    /** Successes, failures and p99 since the previous call. */
    fun takeWindow(): Window {
        val snapshot = Window(windowOk, windowKo, Latencies(window).p99)
        window.reset()
        windowOk = 0
        windowKo = 0
        return snapshot
    }

    fun result(
        scenario: String,
        profile: LoadProfile,
        startedAt: Instant,
        elapsed: Duration,
        abandoned: Long,
    ): LoadTestResult =
        LoadTestResult(
            scenario = scenario,
            profile = profile,
            startedAt = startedAt,
            elapsed = elapsed,
            steps = stepOrder.entries.sortedBy { it.value }.map { it.key },
            stats =
                accumulated.mapValues { (key, acc) ->
                    StepStats(
                        ok = acc.latency.totalCount,
                        ko = acc.ko,
                        failures =
                            live
                                .getValue(key)
                                .failures
                                .mapValues { it.value.sum() }
                                .toSortedMap(),
                        failureDetails = StepStats.sortedDetails(live.getValue(key).messages.mapValues { it.value.sum() }),
                        successLatency = Latencies(acc.latency),
                        failureLatency = Latencies(acc.failedLatency),
                    )
                },
            timeline = timeline.sortedWith(compareBy({ it.second }, { it.step ?: "" })),
            clientLag = lagAccumulated.mapValues { Latencies(it.value) },
            dropped = dropped.mapValues { it.value.sum() },
            runs = runs.mapValues { it.value.sum() },
            failedRuns = failedRuns.mapValues { it.value.sum() },
            runDurations = runDurationAccumulated.mapValues { Latencies(it.value) },
            abandoned = abandoned,
        )

    private fun point(
        second: Int,
        step: String?,
        histogram: Histogram,
        ok: Long,
        ko: Long,
    ): TimelinePoint {
        val latencies = Latencies(histogram)
        return TimelinePoint(second, step, ok, ko, histogram.totalCount, latencies.p50, latencies.p95, latencies.p99, latencies.max)
    }

    private fun toMicros(nanos: Long): Long = (nanos / 1_000).coerceAtLeast(0)
}
