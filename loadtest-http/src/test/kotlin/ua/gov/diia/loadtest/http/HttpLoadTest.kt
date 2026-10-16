package ua.gov.diia.loadtest.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import ua.gov.diia.loadtest.LoadTestContext
import ua.gov.diia.loadtest.LoadTestFailedException
import ua.gov.diia.loadtest.LoadTestMain
import ua.gov.diia.loadtest.LoadTestResult
import ua.gov.diia.loadtest.LoadTesting
import ua.gov.diia.loadtest.Phases
import ua.gov.diia.loadtest.RunOptions
import ua.gov.diia.loadtest.executeLoadTest
import ua.gov.diia.loadtest.loadProfile
import ua.gov.diia.loadtest.local.LocalEnvironmentException
import ua.gov.diia.loadtest.scenario
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpTimeoutException
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HttpLoadTest {
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newVirtualThreadPerTaskExecutor()
            createContext("/") { exchange -> exchange.use { serve(it) } }
            start()
        }
    private val baseUrl = "http://localhost:${server.address.port}"

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `runs an HTTP scenario, classifying unexpected statuses`() =
        runBlocking {
            HttpTarget.connect(HttpSettings(baseUrl)).use { http ->
                val result =
                    executeLoadTest(
                        scenario("lists") {
                            step("get ok") { http.send(http.request("/ok").build()) }
                            step("get missing") { http.send(http.request("/missing").build()) }
                        },
                        loadProfile { constant(30.0, 1.seconds) },
                        RunOptions(failureClassifier = HttpFailureClassifier, onProgress = {}),
                    )
                assertEquals(30L, result.stats(step = "get ok").ok)
                assertEquals(mapOf("HTTP_404" to 30L), result.stats(step = "get missing").failures)
            }
        }

    @Test
    fun `expected statuses replace the 2xx default`() =
        runBlocking {
            HttpTarget.connect(HttpSettings(baseUrl)).use { http ->
                val conditional = http.request("/etag").header("If-None-Match", "\"v1\"").build()
                assertEquals(304, http.send(conditional, 200, 304).statusCode())
                val failure = assertFailsWith<HttpStatusException> { http.send(conditional) }
                assertEquals(304, failure.status)
                assertEquals("/etag", failure.uri.path)
            }
        }

    @Test
    fun `sends the body and returns the response body`() =
        runBlocking {
            HttpTarget.connect(HttpSettings(baseUrl)).use { http ->
                val json = BodyPublishers.ofString("""{"a":1}""")
                val request =
                    http
                        .request("echo")
                        .header("Content-Type", "application/json")
                        .POST(json)
                        .build()
                assertEquals("""{"a":1}""", http.send(request).body())
                assertEquals("""{"a":1}""", http.sendBlocking(request).body())
            }
        }

    @Test
    fun `paths resolve against the base URL, absolute URLs are kept`() {
        val http = HttpTarget.connect(HttpSettings("https://host.example/prefix/"))
        http.use {
            assertEquals(URI("https://host.example/prefix/trust"), it.uri("/trust"))
            assertEquals(URI("https://host.example/prefix/trust"), it.uri("trust"))
            assertEquals(URI("http://other:8080/x"), it.uri("http://other:8080/x"))
        }
        assertEquals(443, HttpSettings("https://host.example/prefix/").port)
        assertEquals(80, HttpSettings("http://host.example").port)
        assertEquals(8081, HttpSettings("http://host.example:8081").port)
        assertFailsWith<IllegalArgumentException> { HttpSettings("host.example:8080") }
    }

    @Test
    fun `classifier finds the cause anywhere in the chain`() {
        val status = HttpStatusException(503, "GET", URI("http://h/x"), "down")
        assertEquals("HTTP_503", HttpFailureClassifier.classify(status))
        assertEquals("HTTP_503", HttpFailureClassifier.classify(IllegalStateException("wrapped", status)))
        assertEquals("TIMEOUT", HttpFailureClassifier.classify(HttpTimeoutException("request timed out")))
        assertEquals("CONNECT_TIMEOUT", HttpFailureClassifier.classify(HttpConnectTimeoutException("connect timed out")))
        assertEquals("CONNECTION_REFUSED", HttpFailureClassifier.classify(ConnectException()))
        assertEquals("IllegalArgumentException", HttpFailureClassifier.classify(IllegalArgumentException()))
        assertEquals("HTTP 503: down", status.message)
    }

    @Test
    fun `an HTTP definition connects after beforeAll, applies timeoutMs and closes the client`() {
        val events = mutableListOf<String>()
        val definition =
            object : HttpLoadTestDefinition("http load test") {
                init {
                    addScenario("slow", "A request the server answers after a second") {
                        val http = target()
                        events += "target ${http.timeout}"
                        scenario("slow") {
                            step("slow get") { http.send(http.request("/slow").build()) }
                        }
                    }
                }

                override fun profile() = loadProfile { constant(5.0, 1.seconds, Phases.STEADY) }

                override fun beforeAll(context: LoadTestContext) {
                    events += "beforeAll"
                }

                override fun afterAll(
                    context: LoadTestContext,
                    result: LoadTestResult,
                ) {
                    events += "failures ${result.stats(step = "slow get").failures}"
                }
            }
        System.setProperty("baseUrl", baseUrl)
        System.setProperty("timeoutMs", "100")
        try {
            assertFailsWith<LoadTestFailedException> { LoadTestMain.run(definition) }
        } finally {
            System.clearProperty("baseUrl")
            System.clearProperty("timeoutMs")
        }
        assertEquals(listOf("beforeAll", "target 100ms", "failures {TIMEOUT=5}"), events)
    }

    @Test
    fun `a Java scenario sends blocking requests on virtual threads`() =
        runBlocking {
            HttpTarget.connect(HttpSettings(baseUrl)).use { http ->
                val result =
                    executeLoadTest(
                        LoadTesting.scenario("blocking") { run -> run.step("get ok") { http.sendBlocking(http.request("/ok").build()) } },
                        loadProfile { constant(20.0, 1.seconds) },
                        RunOptions(failureClassifier = HttpFailureClassifier, onProgress = {}),
                    )
                assertEquals(20L, result.stats(step = "get ok").ok)
            }
        }

    @Test
    fun `fails fast when nothing listens on the base URL`() {
        val definition =
            object : HttpLoadTestDefinition("unreachable") {
                init {
                    addScenario("any", "Never runs") {
                        val http = target()
                        scenario("any") { step("get") { http.send(http.request("/").build()) } }
                    }
                }
            }
        System.setProperty("baseUrl", "http://localhost:1")
        try {
            val failure = assertFailsWith<LocalEnvironmentException> { LoadTestMain.run(definition) }
            assertTrue("Nothing is listening on localhost:1" in failure.message.orEmpty(), failure.message)
        } finally {
            System.clearProperty("baseUrl")
        }
    }

    private fun serve(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/ok" -> {
                respond(exchange, 200, "ok")
            }

            "/echo" -> {
                respond(exchange, 200, exchange.requestBody.readAllBytes().decodeToString())
            }

            "/etag" -> {
                if (exchange.requestHeaders.getFirst("If-None-Match") == "\"v1\"") {
                    exchange.sendResponseHeaders(304, -1)
                } else {
                    respond(exchange, 200, "list")
                }
            }

            "/slow" -> {
                Thread.sleep(1_000)
                respond(exchange, 200, "late")
            }

            else -> {
                respond(exchange, 404, "")
            }
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }
}
