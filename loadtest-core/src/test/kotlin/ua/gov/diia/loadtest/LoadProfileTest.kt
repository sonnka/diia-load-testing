package ua.gov.diia.loadtest

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class LoadProfileTest {
    private val steady =
        loadProfile {
            constant(20.0, 60.seconds, Phases.WARMUP)
            ramp(20.0, 150.0, 60.seconds, Phases.RAMP)
            constant(150.0, 180.seconds, Phases.STEADY)
        }

    @Test
    fun `total arrivals is the area under the rate curve`() {
        assertEquals(1_200L + 5_100L + 27_000L, steady.totalArrivals)
        assertEquals(300.seconds, steady.duration)
        assertEquals(listOf(Phases.WARMUP, Phases.RAMP, Phases.STEADY), steady.phases)
    }

    @Test
    fun `due time is the exact inverse of the arrival integral, across every stage`() {
        for (n in listOf(1L, 600L, 1_200L, 1_201L, 3_000L, 6_300L, 6_301L, 20_000L, steady.totalArrivals)) {
            val due = steady.dueTimeOf(n)!!
            assertTrue(abs(steady.expectedArrivals(due) - n) < 1e-6, "arrival $n due at $due")
        }
        assertNull(steady.dueTimeOf(steady.totalArrivals + 1))
        assertNull(steady.dueTimeOf(0))
    }

    @Test
    fun `due times are strictly increasing and evenly spaced at a constant rate`() {
        val constant = loadProfile { constant(100.0, 2.seconds) }
        val dues = (1L..constant.totalArrivals).map { constant.dueTimeOf(it)!! }
        dues.zipWithNext().forEach { (a, b) -> assertTrue(abs((b - a) - 0.01) < 1e-9) }
    }

    @Test
    fun `ramps that start at zero or go down are handled`() {
        val fromZero = loadProfile { ramp(0.0, 100.0, 10.seconds) }
        assertEquals(500L, fromZero.totalArrivals)
        assertTrue(abs(fromZero.dueTimeOf(500)!! - 10.0) < 1e-6)

        val down = loadProfile { ramp(100.0, 0.0, 10.seconds) }
        assertEquals(500L, down.totalArrivals)
        (1L..500L).map { down.dueTimeOf(it)!! }.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
    }

    @Test
    fun `phase and rate follow the stages`() {
        assertEquals(Phases.WARMUP, steady.phaseAt(0.0))
        assertEquals(Phases.RAMP, steady.phaseAt(60.0))
        assertEquals(Phases.STEADY, steady.phaseAt(299.0))
        assertEquals(Phases.STEADY, steady.phaseAt(1_000.0))
        assertEquals(85.0, steady.rateAt(90.0), 1e-9)
        assertEquals(0.0, steady.rateAt(300.0))
    }

    @Test
    fun `zero-duration stages are skipped, an empty profile is rejected`() {
        val profile =
            loadProfile {
                constant(20.0, Duration.ZERO, Phases.WARMUP)
                constant(10.0, 1.seconds)
            }
        assertEquals(listOf(Phases.STEADY), profile.phases)
        assertFailsWith<IllegalArgumentException> { loadProfile { } }
    }

    @Test
    fun `settings build the standard shapes`() {
        val stress = ProfileSettings(mode = LoadMode.STRESS, warmupDuration = 10.seconds, peakRampDuration = 20.seconds).toProfile()
        assertEquals(listOf(Phases.WARMUP, Phases.STRESS), stress.phases)
        assertEquals(LoadMode.STRESS, LoadMode.parse("ramp"))
    }
}
