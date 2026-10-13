package ua.gov.diia.loadtest

import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Java facade, exercised the way Java code calls it (explicit SAM types, java.time durations). */
class JavaApiTest {
    private val quiet = LoadTesting.options().onProgress {}

    @Test
    fun `blocking scenarios run on virtual threads with the same step semantics`() {
        val virtual = ConcurrentHashMap.newKeySet<Boolean>()
        val scenario =
            LoadTesting.scenario(
                "blocking",
                ScenarioBody { run ->
                    val token =
                        run.step(
                            "first",
                            StepCall {
                                Thread.sleep(20)
                                virtual += Thread.currentThread().isVirtual
                                "token"
                            },
                        )
                    run.step("second", StepAction { check(token.isEmpty()) { "boom" } })
                    run.step("third", StepAction { error("must not run") })
                },
            )
        val profile = LoadTesting.profile().constant(50.0, Duration.ofSeconds(1)).build()

        val result = LoadTesting.run(scenario, profile, quiet)

        assertEquals(50L, result.stats(step = "first").ok)
        assertTrue(result.stats(step = "first").latency.percentileMillis(50.0) >= 20.0)
        assertEquals(mapOf("IllegalStateException" to 50L), result.stats(step = "second").failures)
        assertEquals(0L, result.stats(step = "third").total)
        assertEquals(setOf(true), virtual)
    }

    @Test
    fun `assertion spec, finish and the failure it throws`() {
        val result =
            LoadTesting.run(
                LoadTesting.scenario("sleepy", ScenarioBody { run -> run.step("call", StepAction { Thread.sleep(5) }) }),
                LoadTesting
                    .profile()
                    .constant(20.0, Duration.ofSeconds(1), Phases.WARMUP)
                    .constant(20.0, Duration.ofSeconds(1))
                    .build(),
                quiet,
            )
        val passing =
            LoadTesting
                .assertions()
                .phase(Phases.STEADY) { it.eachStep { c -> c.p99Below(Duration.ofSeconds(1)).errorRateAtMost(0.0) } }
                .noDroppedArrivals()
                .build()
                .verify(result)
        val failing =
            LoadTesting
                .assertions()
                .allPhases { it.step("call") { c -> c.maxBelow(Duration.ofMillis(1)) } }
                .build()
                .verify(result)
        val dir = Files.createTempDirectory("loadtest")

        assertTrue(passing.passed, passing.results.joinToString("\n"))
        assertTrue(Files.exists(result.finish("ok", passing, dir).html))
        val error = assertFailsWith<LoadTestFailedException> { result.finish("fail", failing, dir) }
        assertTrue("[all phases] call: max < 1.0ms" in error.message!!)
    }

    @Test
    fun `props read typed system properties with defaults`() {
        System.setProperty("loadtest.test.rate", "12.5")
        assertEquals(12.5, Props.getDouble("loadtest.test.rate", 1.0))
        assertEquals(7, Props.getInt("loadtest.test.missing", 7))
        assertEquals("x", Props.get("loadtest.test.missing", "x"))
    }
}
