package ua.gov.diia.loadtest

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

/** Creates a scenario for a run; called after [LoadTestDefinition.beforeAll], so it can use what that set up. */
public fun interface ScenarioFactory {
    @Throws(Exception::class)
    public fun create(context: LoadTestContext): Scenario
}

/**
 * A service's whole load test as a declaration - what to run, with which parameters, how to judge it.
 * The library does the rest: reads the settings ([Config]: `-D` > preset > `loadtest.properties` >
 * default), builds the profile, runs the selected scenario (`-Dscenario=<name>`, default: the first
 * one added), asserts, writes the reports and sets the exit code; `./gradlew loadTestHelp` lists the
 * scenarios, presets and parameters. With the Gradle plugin a service only sets
 * `loadTest { definition = "<class>" }` - no `main` of its own.
 */
public abstract class LoadTestDefinition
    @JvmOverloads
    constructor(
        public val title: String,
        public val description: String = "",
    ) {
        private val declared = mutableListOf<Parameter<*>>()
        private val scenarioEntries = LinkedHashMap<String, ScenarioEntry>()
        private var current: LoadTestContext? = null

        /** Parameters every load test has; listed under "Run" in `loadTestHelp`. */
        public val runParameters: List<Parameter<*>>

        /** `-Dlocal=true`: the target is a local instance; [beforeAll] decides what that means (start it, stub its dependencies). */
        public val local: Parameter<Boolean> =
            Parameter.boolean(
                "local",
                false,
                "Target a local instance, which the load test sets up itself",
            )
        public val p99Ms: Parameter<Long> =
            Parameter.long("p99Ms", DEFAULT_P99_MS, "steady: p99 latency limit per step in the steady phase, ms")
        public val maxMs: Parameter<Long> =
            Parameter.long("maxMs", DEFAULT_MAX_MS, "steady: max latency limit per step in the steady phase, ms")
        public val maxErrorRate: Parameter<Double> =
            Parameter.double("maxErrorRate", 0.0, "steady: allowed share of failed calls per step, 0.0..1.0")
        public val maxRunErrorRate: Parameter<Double> =
            Parameter.double("maxRunErrorRate", 1.0, "steady: allowed share of failed runs (whole scenarios), 0.0..1.0; 1 = not checked")
        public val maxClientLagMs: Parameter<Long> =
            Parameter.long(
                "maxClientLagMs",
                DEFAULT_MAX_CLIENT_LAG_MS,
                "steady: p99 of client start lag in the steady phase, ms; above it the generator, not the server, is the bottleneck",
            )
        public val maxInFlight: Parameter<Int> =
            Parameter.int("maxInFlight", RunOptions().maxInFlight, "Cap on concurrent runs; arrivals beyond it are dropped")

        init {
            runParameters = listOf(local, p99Ms, maxMs, maxErrorRate, maxRunErrorRate, maxClientLagMs, maxInFlight)
        }

        /** Parameters declared by the definition (and by base classes such as the gRPC one), in declaration order. */
        public val parameters: List<Parameter<*>> get() = declared.toList()

        /** Scenario names and descriptions, in the order added; the first is the default. */
        public val scenarios: Map<String, String> get() = scenarioEntries.mapValues { it.value.description }

        /** The context of the run in progress: available from [beforeAll] until the run's resources are closed. */
        protected val context: LoadTestContext
            get() = checkNotNull(current) { "The load test context is only available during a run (beforeAll .. afterAll)" }

        protected fun stringParam(
            name: String,
            default: String,
            description: String,
        ): Parameter<String> = declare(Parameter.string(name, default, description))

        protected fun intParam(
            name: String,
            default: Int,
            description: String,
        ): Parameter<Int> = declare(Parameter.int(name, default, description))

        protected fun longParam(
            name: String,
            default: Long,
            description: String,
        ): Parameter<Long> = declare(Parameter.long(name, default, description))

        protected fun doubleParam(
            name: String,
            default: Double,
            description: String,
        ): Parameter<Double> = declare(Parameter.double(name, default, description))

        protected fun booleanParam(
            name: String,
            default: Boolean,
            description: String,
        ): Parameter<Boolean> = declare(Parameter.boolean(name, default, description))

        /** Adds a scenario selectable with `-Dscenario=<name>`; the first one added is the default. */
        protected fun addScenario(
            name: String,
            description: String,
            factory: ScenarioFactory,
        ) {
            require(scenarioEntries.put(name, ScenarioEntry(description, factory)) == null) { "Scenario '$name' added twice" }
        }

        /**
         * Runs before the scenario is created: check or start what the run depends on (see
         * [LoadTestContext.environment]). Anything registered with [LoadTestContext.closeLater] is closed
         * after the run, also when it fails or is interrupted.
         */
        @Throws(Exception::class)
        public open fun beforeAll(context: LoadTestContext) {
        }

        /** Runs after the load, before the verdict and the reports. */
        @Throws(Exception::class)
        public open fun afterAll(
            context: LoadTestContext,
            result: LoadTestResult,
        ) {
        }

        /** The arrival profile; by default the standard steady/stress shapes from [ProfileSettings]. */
        public open fun profile(): LoadProfile = ProfileSettings.fromSystemProperties().toProfile()

        /** Turns call failures into report codes, e.g. gRPC status names. */
        public open fun failureClassifier(): FailureClassifier = FailureClassifier.Default

        /** Per-step p99 limits that replace [p99Ms] for those steps, e.g. a step that is slower by design. */
        public open fun stepP99Limits(): Map<String, java.time.Duration> = emptyMap()

        /**
         * Steps the selected scenario must record in the steady phase: one without a single call fails the run
         * instead of passing unnoticed (e.g. a conditional step that a bug stopped calling).
         */
        public open fun requiredSteps(context: LoadTestContext): Set<String> = emptySet()

        /** Pass/fail criteria; null means the run is not judged. By default [defaultAssertions] in steady mode, none in stress mode. */
        public open fun assertions(context: LoadTestContext): Assertions? =
            if (context.mode == LoadMode.STEADY) defaultAssertions(context) else null

        /**
         * Steady phase: every step's p99 below [p99Ms] (or its [stepP99Limits] entry) and max below [maxMs];
         * every [requiredSteps] entry recorded; client start lag p99 below [maxClientLagMs].
         * All phases: every step's error rate at most [maxErrorRate], the failed-run share at most
         * [maxRunErrorRate]; no dropped or abandoned runs.
         */
        public fun defaultAssertions(context: LoadTestContext): Assertions {
            val overrides = stepP99Limits()
            val steady = mutableListOf<Rule>()
            requiredSteps(context).forEach { step ->
                steady += Rule.Steps(Phases.STEADY, StepSelector.Named(step), listOf(minRequestsCriterion(1)))
            }
            steady += Rule.Steps(Phases.STEADY, StepSelector.Each(overrides.keys), listOf(p99(p99Ms.value.milliseconds)))
            overrides.forEach { (step, limit) ->
                steady += Rule.Steps(Phases.STEADY, StepSelector.IfRecorded(step), listOf(p99(limit.toKotlinDuration())))
            }
            val max = latencyCriterion("max", maxMs.value.milliseconds) { it.max }
            steady += Rule.Steps(Phases.STEADY, StepSelector.Each.ALL_STEPS, listOf(max))
            steady += Rule.ClientLag(Phases.STEADY, maxClientLagMs.value.milliseconds)
            val errors = Rule.Steps(null, StepSelector.Each.ALL_STEPS, listOf(errorRateCriterion(maxErrorRate.value)))
            val runs = listOfNotNull(Rule.RunErrorRate(null, maxRunErrorRate.value).takeIf { maxRunErrorRate.value < 1.0 })
            return Assertions(steady + errors + runs + Rule.NoDropped)
        }

        private fun p99(limit: kotlin.time.Duration) = latencyCriterion("p99", limit) { it.p99 }

        private fun <T : Any> declare(parameter: Parameter<T>): Parameter<T> {
            val taken = (runParameters + declared + ProfileSettings.parameters()).map { it.name } + RESERVED
            require(parameter.name !in taken) { "Parameter '${parameter.name}' is already defined" }
            declared += parameter
            return parameter
        }

        internal fun selectedScenario(): String {
            val first = checkNotNull(scenarioEntries.keys.firstOrNull()) { "$title adds no scenarios (addScenario)" }
            val name = Config.raw(SCENARIO) ?: return first
            require(name in scenarioEntries) { "Unknown scenario '$name'; available: ${scenarioEntries.keys.joinToString()}" }
            return name
        }

        internal fun createScenario(context: LoadTestContext): Scenario = scenarioEntries.getValue(context.scenario).factory.create(context)

        internal fun bind(context: LoadTestContext?) {
            current = context
        }

        private class ScenarioEntry(
            val description: String,
            val factory: ScenarioFactory,
        )

        public companion object {
            public const val SCENARIO: String = "scenario"
            private const val DEFAULT_P99_MS = 200L
            private const val DEFAULT_MAX_MS = 2_000L
            private const val DEFAULT_MAX_CLIENT_LAG_MS = 100L
            private val RESERVED = listOf(SCENARIO, Config.PRESET)
        }
    }
