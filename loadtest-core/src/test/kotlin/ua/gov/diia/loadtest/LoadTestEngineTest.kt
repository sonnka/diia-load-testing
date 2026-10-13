package ua.gov.diia.loadtest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Runs against the real clock: a few seconds in total. */
class LoadTestEngineTest {
    private val quiet = RunOptions(onProgress = {})

    @Test
    fun `starts every scheduled arrival and measures only the timed block`() =
        runBlocking {
            val profile = loadProfile { constant(100.0, 2.seconds) }
            val result =
                executeLoadTest(
                    scenario("delay") {
                        delay(50) // preparation: must not show up in the step latency
                        step("call") { delay(20) }
                    },
                    profile,
                    quiet,
                )
            val stats = result.stats(step = "call")
            assertEquals(profile.totalArrivals, stats.ok)
            assertEquals(0L, stats.ko)
            assertTrue(stats.latency.p50 >= 20.milliseconds && stats.latency.p50 < 45.milliseconds, "p50=${stats.latency.p50}")
            assertTrue(result.clientLag().p99 < 50.milliseconds, "lag p99=${result.clientLag().p99}")
        }

    @Test
    fun `slow failures count in the latency, whole runs are timed start to end`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("mixed") {
                        delay(30) // preparation: in the whole run, not in the step
                        step("call") {
                            if (runId % 2 == 0L) {
                                withTimeout(200) { delay(1_000) } // a slow failure, like DEADLINE_EXCEEDED
                            } else {
                                delay(10)
                            }
                        }
                    },
                    loadProfile { constant(20.0, 1.seconds) },
                    quiet,
                )
            val stats = result.stats(step = "call")
            assertEquals(10L, stats.ok)
            assertEquals(10L, stats.ko)
            assertTrue(stats.successLatency.max < 100.milliseconds, "success max=${stats.successLatency.max}")
            assertEquals(10L, stats.failureLatency.count)
            assertTrue(stats.failureLatency.p50 >= 200.milliseconds, "failed p50=${stats.failureLatency.p50}")
            assertEquals(20L, stats.latency.count)
            assertTrue(stats.latency.p99 >= 200.milliseconds, "the slow failures must show in p99: ${stats.latency.p99}")
            assertEquals(20L, result.runs())
            assertEquals(10L, result.failedRuns())
            assertTrue(result.runDuration().p50 >= 40.milliseconds, "whole run includes preparation: ${result.runDuration().p50}")
            val verdict = assertions { allPhases { runP99Below(150.milliseconds) } }.verify(result)
            assertFalse(verdict.passed, verdict.results.joinToString())
            assertTrue("failed calls took p50" in ConsoleReport.render(result))
        }

    @Test
    fun `a failing step stops its run and is the only failure recorded`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("chain") {
                        val token = step("first") { "token" }
                        step("second") { check(token.isEmpty()) { "boom" } }
                        step("third") { error("must not run") }
                    },
                    loadProfile { constant(50.0, 1.seconds) },
                    quiet,
                )
            assertEquals(50L, result.stats(step = "first").ok)
            assertEquals(50L, result.stats(step = "second").ko)
            assertEquals(mapOf("IllegalStateException" to 50L), result.stats(step = "second").failures)
            assertEquals(0L, result.stats(step = "third").total)
            assertEquals(listOf("first", "second"), result.steps)
        }

    @Test
    fun `timeouts, assertion errors and failures outside steps are recorded instead of killing the run`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("mixed") {
                        when (runId % 3) {
                            0L -> step("timeout") { withTimeout(1) { delay(100) } }
                            1L -> step("assert") { throw AssertionError("nope") }
                            else -> error("before any step")
                        }
                    },
                    loadProfile { constant(30.0, 1.seconds) },
                    quiet,
                )
            assertEquals(10L, result.stats(step = "timeout").failures["TimeoutCancellationException"])
            assertEquals(10L, result.stats(step = "assert").failures["AssertionError"])
            assertEquals(10L, result.stats(step = OUTSIDE_STEPS).failures["IllegalStateException"])
        }

    @Test
    fun `arrivals beyond max in-flight are dropped, stuck runs are abandoned at drain timeout`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("stuck") { step("hang") { delay(60_000) } },
                    loadProfile { constant(20.0, 1.seconds) },
                    quiet.copy(maxInFlight = 5, drainTimeout = 200.milliseconds),
                )
            assertEquals(15L, result.totalDropped)
            assertEquals(5L, result.abandoned)
            assertTrue(result.elapsed < 3.seconds)
        }

    @Test
    fun `phases, assertions and reports`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("phased") { step("call") { delay(if (phase == Phases.WARMUP) 30 else 1) } },
                    loadProfile {
                        constant(20.0, 1.seconds, Phases.WARMUP)
                        constant(20.0, 1.seconds, Phases.STEADY)
                    },
                    quiet,
                )
            assertEquals(listOf(Phases.WARMUP, Phases.STEADY), result.phases)

            val verdict =
                assertions {
                    phase(Phases.STEADY) { eachStep { p99Below(20.milliseconds) } }
                    phase(Phases.WARMUP) { step("call") { p99Below(20.milliseconds) } }
                    allPhases { allSteps { errorRateAtMost(0.0) } }
                    phase("missing") { eachStep { minRequests(1) } }
                    noDroppedArrivals()
                }.verify(result)
            assertEquals(listOf(true, false, true, false, true), verdict.results.map { it.passed }, verdict.results.joinToString("\n"))
            assertFalse(verdict.passed)

            val dir = Files.createTempDirectory("loadtest")
            val files = result.writeReports(dir, title = "it's <a> test", verdict = verdict)
            val html = Files.readString(files.html)
            assertTrue("it&#39;s &lt;a&gt; test" in html)
            assertTrue("\"passed\":false" in Files.readString(files.json))
            assertTrue("Phase 'steady'" in ConsoleReport.render(result, verdict))
        }
}
