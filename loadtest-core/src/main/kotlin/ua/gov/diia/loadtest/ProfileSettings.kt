package ua.gov.diia.loadtest

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

public enum class LoadMode {
    /** Warm up, ramp to the target rate, hold it. The run meant for pass/fail assertions. */
    STEADY,

    /** Warm up, then keep ramping to a peak rate. Meant for finding the breaking point by watching the output. */
    STRESS,
    ;

    public companion object {
        /** Accepts `steady` and `stress` (or the legacy `ramp`), case-insensitively. */
        public fun parse(value: String): LoadMode =
            when (value.trim().lowercase()) {
                "steady" -> STEADY
                "stress", "ramp" -> STRESS
                else -> throw IllegalArgumentException("Unknown load mode '$value', expected 'steady' or 'stress'")
            }
    }
}

/** The two standard profile shapes, with every knob overridable from system properties. */
public data class ProfileSettings(
    public val mode: LoadMode = LoadMode.STEADY,
    public val warmupRate: Double = 20.0,
    public val warmupDuration: Duration = 60.seconds,
    public val targetRate: Double = 150.0,
    public val rampDuration: Duration = 60.seconds,
    public val steadyDuration: Duration = 180.seconds,
    public val peakRate: Double = 600.0,
    public val peakRampDuration: Duration = 360.seconds,
) {
    public fun toProfile(): LoadProfile =
        when (mode) {
            LoadMode.STEADY -> {
                loadProfile {
                    constant(warmupRate, warmupDuration, Phases.WARMUP)
                    ramp(warmupRate, targetRate, rampDuration, Phases.RAMP)
                    constant(targetRate, steadyDuration, Phases.STEADY)
                }
            }

            LoadMode.STRESS -> {
                loadProfile {
                    constant(warmupRate, warmupDuration, Phases.WARMUP)
                    ramp(warmupRate, peakRate, peakRampDuration, Phases.STRESS)
                }
            }
        }

    public companion object {
        /** System property names read by [fromSystemProperties]; durations are in seconds. */
        @JvmField
        public val PROPERTY_NAMES: List<String> =
            listOf(
                "mode",
                "warmupRate",
                "warmupSeconds",
                "targetRate",
                "rampSeconds",
                "steadySeconds",
                "peakRate",
                "peakRampSeconds",
            )

        /** The settings [fromSystemProperties] reads, described for `loadTestHelp`. */
        internal fun parameters(defaults: ProfileSettings = ProfileSettings()): List<Parameter<*>> =
            listOf(
                Parameter.string(
                    "mode",
                    defaults.mode.name.lowercase(),
                    "steady: warm up, ramp to targetRate, hold it (asserted); stress: warm up, ramp to peakRate (not asserted)",
                ),
                Parameter.double("warmupRate", defaults.warmupRate, "Arrivals/s during warm-up"),
                Parameter.double("warmupSeconds", defaults.warmupDuration.toDouble(DurationUnit.SECONDS), "Warm-up length, s"),
                Parameter.double("targetRate", defaults.targetRate, "steady: arrivals/s held in the steady phase"),
                Parameter.double("rampSeconds", defaults.rampDuration.toDouble(DurationUnit.SECONDS), "steady: warm-up to target ramp, s"),
                Parameter.double("steadySeconds", defaults.steadyDuration.toDouble(DurationUnit.SECONDS), "steady: steady phase length, s"),
                Parameter.double("peakRate", defaults.peakRate, "stress: arrivals/s at the end of the ramp"),
                Parameter.double(
                    "peakRampSeconds",
                    defaults.peakRampDuration.toDouble(DurationUnit.SECONDS),
                    "stress: warm-up to peak ramp, s",
                ),
            )

        /** [defaults], overridden by whichever of [PROPERTY_NAMES] are set (see [Config] for where they come from). */
        @JvmStatic
        @JvmOverloads
        public fun fromSystemProperties(defaults: ProfileSettings = ProfileSettings()): ProfileSettings {
            fun double(
                name: String,
                default: Double,
            ): Double = Config.raw(name)?.toDouble() ?: default

            fun seconds(
                name: String,
                default: Duration,
            ): Duration = Config.raw(name)?.toDouble()?.seconds ?: default

            return ProfileSettings(
                mode = Config.raw("mode")?.let(LoadMode::parse) ?: defaults.mode,
                warmupRate = double("warmupRate", defaults.warmupRate),
                warmupDuration = seconds("warmupSeconds", defaults.warmupDuration),
                targetRate = double("targetRate", defaults.targetRate),
                rampDuration = seconds("rampSeconds", defaults.rampDuration),
                steadyDuration = seconds("steadySeconds", defaults.steadyDuration),
                peakRate = double("peakRate", defaults.peakRate),
                peakRampDuration = seconds("peakRampSeconds", defaults.peakRampDuration),
            )
        }
    }
}
