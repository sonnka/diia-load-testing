package ua.gov.diia.loadtest

import java.util.Locale
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.DurationUnit

// Absorbs floating-point error in the arrival integrals (e.g. 20.0 * 60 summing to 1199.999…).
private const val EPSILON = 1e-6

/** Well-known phase names used by [ProfileSettings]; any other name works just as well. */
public object Phases {
    public const val WARMUP: String = "warmup"
    public const val RAMP: String = "ramp"
    public const val STEADY: String = "steady"
    public const val STRESS: String = "stress"
}

/**
 * One stage of an open-workload arrival schedule: the arrival rate (new scenario runs per second)
 * moves linearly from [fromRate] to [toRate] over [duration]. Every run started during this stage
 * is reported under [phase], so assertions can target e.g. only the steady state.
 */
public data class Stage(
    public val phase: String,
    public val fromRate: Double,
    public val toRate: Double,
    public val duration: Duration,
) {
    init {
        require(phase.isNotBlank()) { "phase must not be blank" }
        require(fromRate >= 0.0 && toRate >= 0.0) { "rates must be >= 0, got $fromRate -> $toRate" }
        require(duration.isPositive()) { "duration must be > 0, got $duration" }
    }

    internal val seconds: Double = duration.toDouble(DurationUnit.SECONDS)
    private val slope: Double = (toRate - fromRate) / seconds

    /** Arrivals due within the first [t] seconds of this stage: the area under the rate line. */
    internal fun arrivalsWithin(t: Double): Double {
        val c = t.coerceIn(0.0, seconds)
        return fromRate * c + slope * c * c / 2.0
    }

    internal fun rateAt(t: Double): Double = fromRate + slope * t.coerceIn(0.0, seconds)

    /**
     * Inverse of [arrivalsWithin]: the offset at which the [n]-th arrival of this stage is due.
     * Solves `fromRate*t + slope*t²/2 = n` in the cancellation-free form `2n / (r0 + √(r0² + 2·slope·n))`,
     * which also covers the constant-rate case (`slope = 0` gives `n / r0`).
     */
    internal fun offsetOf(n: Double): Double {
        if (n <= 0.0) return 0.0
        val root = sqrt((fromRate * fromRate + 2.0 * slope * n).coerceAtLeast(0.0))
        return (2.0 * n / (fromRate + root)).coerceAtMost(seconds)
    }

    override fun toString(): String =
        if (fromRate == toRate) {
            String.format(Locale.ROOT, "%s: constant %.1f/s for %s", phase, fromRate, duration)
        } else {
            String.format(Locale.ROOT, "%s: %.1f/s -> %.1f/s over %s", phase, fromRate, toRate, duration)
        }
}

/** An ordered list of [Stage]s. Arrivals are spread exactly along the rate curve, not in bursts. */
public class LoadProfile(
    stages: List<Stage>,
) {
    public val stages: List<Stage> = stages.toList()

    init {
        require(this.stages.isNotEmpty()) { "a load profile needs at least one stage with a positive duration" }
    }

    public val duration: Duration = this.stages.fold(Duration.ZERO) { acc, stage -> acc + stage.duration }
    public val phases: List<String> = this.stages.map { it.phase }.distinct()
    private val startOffsets: DoubleArray = DoubleArray(this.stages.size)
    private val arrivalsBefore: DoubleArray = DoubleArray(this.stages.size)

    init {
        var offset = 0.0
        var arrivals = 0.0
        this.stages.forEachIndexed { i, stage ->
            startOffsets[i] = offset
            arrivalsBefore[i] = arrivals
            offset += stage.seconds
            arrivals += stage.arrivalsWithin(stage.seconds)
        }
    }

    private val totalSeconds: Double = duration.toDouble(DurationUnit.SECONDS)

    /** Whole number of runs this profile starts. */
    public val totalArrivals: Long = (expectedArrivals(totalSeconds) + EPSILON).toLong()

    internal fun expectedArrivals(elapsedSeconds: Double): Double {
        val i = stageIndexAt(elapsedSeconds)
        return arrivalsBefore[i] + stages[i].arrivalsWithin(elapsedSeconds - startOffsets[i])
    }

    /** Elapsed seconds at which arrival number [n] (1-based) is due, or null once the profile has no more arrivals. */
    internal fun dueTimeOf(n: Long): Double? {
        if (n < 1 || n > totalArrivals) return null
        val target = n.toDouble()
        for (i in stages.indices) {
            val stage = stages[i]
            val endArrivals = arrivalsBefore[i] + stage.arrivalsWithin(stage.seconds)
            if (target <= endArrivals + EPSILON || i == stages.lastIndex) {
                return startOffsets[i] + stage.offsetOf(target - arrivalsBefore[i])
            }
        }
        return null
    }

    internal fun phaseAt(elapsedSeconds: Double): String = stages[stageIndexAt(elapsedSeconds)].phase

    /** Target arrival rate at [elapsedSeconds]; 0 once the profile is over. */
    internal fun rateAt(elapsedSeconds: Double): Double {
        if (elapsedSeconds >= totalSeconds) return 0.0
        val i = stageIndexAt(elapsedSeconds)
        return stages[i].rateAt(elapsedSeconds - startOffsets[i])
    }

    private fun stageIndexAt(elapsedSeconds: Double): Int {
        for (i in stages.indices.reversed()) {
            if (elapsedSeconds >= startOffsets[i]) return i
        }
        return 0
    }

    override fun toString(): String = stages.joinToString("; ")
}

public class LoadProfileBuilder internal constructor() {
    private val stages = mutableListOf<Stage>()

    /** Holds [rate] arrivals/second for [duration]. A zero duration is skipped, so optional stages can be configured away. */
    public fun constant(
        rate: Double,
        duration: Duration,
        phase: String = Phases.STEADY,
    ) {
        if (duration.isPositive()) stages += Stage(phase, rate, rate, duration)
    }

    /** Moves linearly from [from] to [to] arrivals/second over [duration]. A zero duration is skipped. */
    public fun ramp(
        from: Double,
        to: Double,
        duration: Duration,
        phase: String = Phases.RAMP,
    ) {
        if (duration.isPositive()) stages += Stage(phase, from, to, duration)
    }

    internal fun build(): LoadProfile = LoadProfile(stages)
}

public fun loadProfile(block: LoadProfileBuilder.() -> Unit): LoadProfile = LoadProfileBuilder().apply(block).build()
