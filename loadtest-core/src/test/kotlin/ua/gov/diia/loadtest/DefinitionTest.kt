package ua.gov.diia.loadtest

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DefinitionTest {
    private fun <T> withProperties(
        vararg properties: Pair<String, String>,
        block: () -> T,
    ): T {
        properties.forEach { (name, value) -> System.setProperty(name, value) }
        try {
            return block()
        } finally {
            properties.forEach { System.clearProperty(it.first) }
        }
    }

    /** A definition as a service writes it; the test file's loadtest.properties sets orders=3. */
    private class OrdersLoadTest : LoadTestDefinition("orders load test") {
        val orders = intParam("orders", 10, "Orders per run")
        val events = mutableListOf<String>()
        val closed = AtomicBoolean()
        val seenResult = AtomicReference<LoadTestResult>()

        init {
            addScenario("list", "List orders") { context ->
                events += "create ${context.scenario}"
                scenario("List") { step("List") { delay(2) } }
            }
            addScenario("slow", "Slow by design") {
                scenario("Slow") {
                    step("Fast") { delay(1) }
                    step("Slow") { delay(30) }
                }
            }
        }

        override fun profile(): LoadProfile = loadProfile { constant(20.0, 1.seconds, Phases.STEADY) }

        override fun stepP99Limits(): Map<String, java.time.Duration> = mapOf("Slow" to java.time.Duration.ofSeconds(1))

        override fun beforeAll(context: LoadTestContext) {
            events += "beforeAll local=${context.isLocal}"
            context.closeLater(AutoCloseable { closed.set(true) })
        }

        override fun afterAll(
            context: LoadTestContext,
            result: LoadTestResult,
        ) {
            events += "afterAll"
            seenResult.set(result)
            check(this.context === context)
        }
    }

    @Test
    fun `settings resolve -D over preset over loadtest properties over default`() {
        val definition = OrdersLoadTest()
        assertEquals(3, definition.orders.value)
        assertEquals(Config.FILE, definition.orders.source)
        withProperties(Config.PRESET to "tiny") {
            assertEquals(1, definition.orders.value)
            assertEquals("preset tiny", definition.orders.source)
            assertEquals(7.0, ProfileSettings.fromSystemProperties().targetRate)
            withProperties("orders" to "5") {
                assertEquals(5, definition.orders.value)
                assertEquals("-D", definition.orders.source)
            }
        }
        assertEquals("default", definition.p99Ms.source)
        assertEquals(
            mapOf("warmupRate" to "5", "warmupSeconds" to "2", "rampSeconds" to "2", "targetRate" to "5", "steadySeconds" to "1"),
            Config.presets()["smoke"],
        )
        withProperties(Config.PRESET to "nope") {
            assertFailsWith<IllegalArgumentException> { Config.raw("orders") }
        }
        withProperties("orders" to "many") {
            val error = assertFailsWith<IllegalArgumentException> { definition.orders.value }
            assertTrue("orders" in error.message!!, error.message)
        }
    }

    @Test
    fun `parameters and scenarios are validated`() {
        val definition = OrdersLoadTest()
        assertEquals(listOf("list", "slow"), definition.scenarios.keys.toList())
        assertEquals("list", definition.selectedScenario())
        withProperties(LoadTestDefinition.SCENARIO to "missing") {
            assertFailsWith<IllegalArgumentException> { definition.selectedScenario() }
        }
        assertFailsWith<IllegalArgumentException> {
            object : LoadTestDefinition("clash") {
                val port = intParam("targetRate", 1, "clashes with a profile setting")
            }
        }
        assertFailsWith<IllegalStateException> { object : LoadTestDefinition("empty") {}.selectedScenario() }
    }

    @Test
    fun `a run goes through the hooks, asserts and closes what was registered`() {
        val definition = OrdersLoadTest()

        LoadTestMain.run(definition)

        assertEquals(listOf("beforeAll local=false", "create list", "afterAll"), definition.events)
        assertTrue(definition.closed.get())
        assertEquals(
            20L,
            definition.seenResult
                .get()
                .stats(step = "List")
                .ok,
        )
        assertFailsWith<IllegalStateException> { definition.contextForTest() }
    }

    @Test
    fun `default assertions use p99Ms except where a step has its own limit`() {
        val definition = OrdersLoadTest()
        withProperties(LoadTestDefinition.SCENARIO to "slow", "p99Ms" to "20", "local" to "true") {
            LoadTestMain.run(definition) // Slow (~30 ms) is held to its own 1 s limit, Fast to 20 ms
            assertTrue("beforeAll local=true" in definition.events)
        }
        withProperties(LoadTestDefinition.SCENARIO to "slow", "p99Ms" to "20", "maxMs" to "25") {
            val failure = assertFailsWith<LoadTestFailedException> { LoadTestMain.run(definition) }
            val failed =
                failure.verdict.results
                    .filterNot { it.passed }
                    .map { it.description }
            assertEquals(listOf("[steady] Slow: max < 25.0ms"), failed)
        }
        assertTrue(definition.closed.get(), "resources are closed when assertions fail too")
    }

    @Test
    fun `required steps, failed runs and client lag are judged too`() {
        val definition =
            object : LoadTestDefinition("flaky") {
                init {
                    addScenario("flaky", "Every other run fails") {
                        scenario("Flaky") {
                            step("A") { check(runId % 2 == 1L) { "even run" } }
                        }
                    }
                }

                override fun profile(): LoadProfile = loadProfile { constant(20.0, 1.seconds, Phases.STEADY) }

                override fun requiredSteps(context: LoadTestContext) = setOf("A", "Never")
            }
        val failure =
            withProperties("maxErrorRate" to "1.0", "maxRunErrorRate" to "0.4", "maxClientLagMs" to "0") {
                assertFailsWith<LoadTestFailedException> { LoadTestMain.run(definition) }
            }
        val failed =
            failure.verdict.results
                .filterNot { it.passed }
                .map { it.description }
        assertEquals(
            listOf("[steady] Never", "[steady] client start lag p99 < 0.0ms", "[all phases] failed runs <= 40.00%"),
            failed,
            failure.message,
        )
        val passed =
            failure.verdict.results
                .filter { it.passed }
                .map { it.description }
        assertTrue("[steady] A: requests >= 1" in passed, passed.toString())
    }

    @Test
    fun `the loadTest prefix wins over the plain name`() {
        val definition = OrdersLoadTest()
        withProperties("orders" to "5", "${Config.PREFIX}orders" to "6") {
            assertEquals(6, definition.orders.value)
            assertEquals("-D", definition.orders.source)
        }
    }

    @Test
    fun `stress mode is not judged and help lists everything`() {
        val definition = OrdersLoadTest()
        withProperties("mode" to "stress") {
            assertNull(definition.assertions(LoadTestContext("t", "list", LoadMode.STRESS, definition.profile(), false)))
        }
        val help = withProperties("orders" to "4") { LoadTestMain.help(definition) }
        listOf("list *", "slow", "smoke", "tiny", "targetRate", "p99Ms", "orders", "4 [-D]", "Orders per run").forEach {
            assertTrue(it in help, "'$it' missing from help:\n$help")
        }
    }

    @Test
    fun `definitions load as kotlin objects or no-arg classes`() {
        assertTrue(LoadTestMain.load(ObjectDefinition::class.java.name) === ObjectDefinition)
        assertTrue(LoadTestMain.load(OrdersLoadTest::class.java.name) is OrdersLoadTest)
        assertFailsWith<IllegalArgumentException> { LoadTestMain.load(String::class.java.name) }
        assertFailsWith<IllegalArgumentException> { LoadTestMain.load("no.such.Definition") }
    }
}

object ObjectDefinition : LoadTestDefinition("object")

private fun LoadTestDefinition.contextForTest(): LoadTestContext =
    LoadTestDefinition::class.java
        .getDeclaredMethod("getContext")
        .apply { isAccessible = true }
        .let { method ->
            try {
                method.invoke(this) as LoadTestContext
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }
