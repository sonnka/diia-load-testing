package ua.gov.diia.loadtest.local

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalEnvironmentTest {
    private val http = HttpClient.newHttpClient()

    private fun call(
        method: String,
        url: String,
        body: String = "",
    ): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI(url)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `fake http server answers routes, prefixes and counts requests`() {
        LocalEnvironment().use { env ->
            val fake =
                env
                    .fakeHttp()
                    .post("/take") { FakeResponse.json("""{"echo":"${it.body}","key":"${it.header("X-Key")}"}""") }
                    .get("/lists/{*}") { FakeResponse.text(it.path) }
                    .post("/boom") { error("broken") }

            val take =
                http.send(
                    HttpRequest
                        .newBuilder(URI("${fake.baseUrl}/take"))
                        .header("x-key", "k1")
                        .POST(HttpRequest.BodyPublishers.ofString("hi"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(200, take.statusCode())
            assertEquals("""{"echo":"hi","key":"k1"}""", take.body())
            assertEquals("application/json", take.headers().firstValue("Content-Type").get())
            assertEquals("/lists/10", call("GET", "${fake.baseUrl}/lists/10").body())
            assertEquals(404, call("GET", "${fake.baseUrl}/listsX").statusCode())
            assertEquals(404, call("GET", "${fake.baseUrl}/take").statusCode())
            assertEquals(500, call("POST", "${fake.baseUrl}/boom").statusCode())
            assertEquals(1, fake.requests("POST", "/take"))
            assertEquals(1, fake.requests("GET", "/lists/{*}"))
            assertEquals(2, fake.unmatched)
        }
    }

    @Test
    fun `dependency checks fail with readable messages`() {
        ServerSocket(0).use { socket ->
            val env = LocalEnvironment()
            env.requireListening("Busy", "localhost", socket.localPort)
            val busy = assertFailsWith<IllegalStateException> { env.requireFree("gRPC", socket.localPort) }
            assertTrue("${socket.localPort}" in busy.message!!)
        }
        val freePort = ServerSocket(0).use { it.localPort }
        val missing =
            assertFailsWith<IllegalStateException> {
                LocalEnvironment().requireListening(
                    "MongoDB",
                    "localhost",
                    freePort,
                    "docker compose up",
                )
            }
        assertEquals("MongoDB is not reachable at localhost:$freePort - docker compose up", missing.message)
    }

    @Test
    fun `a service is not started on a busy port or with a missing properties file`() {
        ServerSocket(0).use { socket ->
            val busy =
                assertFailsWith<LocalEnvironmentException> {
                    LocalEnvironment().use {
                        it
                            .service("svc")
                            .classpath("none")
                            .mainClass("None")
                            .waitForPort(socket.localPort)
                            .start()
                    }
                }
            assertEquals(
                "svc: port ${socket.localPort} is already in use on localhost - stop whatever runs there or pick another port",
                busy.message,
            )
        }
        val missing = assertFailsWith<LocalEnvironmentException> { LocalEnvironment().service("svc").springConfig("nope.yaml") }
        assertEquals("svc: no nope.yaml on the load test classpath (src/loadTest/resources)", missing.message)
    }

    @Test
    fun `a service process is started, awaited and stopped`() {
        val port = ServerSocket(0).use { it.localPort }
        val log = Files.createTempDirectory("loadtest").resolve("fake-service.log")
        val env = LocalEnvironment()
        val service =
            env
                .service("fake-service")
                .classpath(System.getProperty("java.class.path"))
                .mainClass(FakeService::class.java.name)
                .jvmArgs("-Dgreeting=a \"quoted\" value")
                .args(port.toString(), """--json=["YQ=="]""", """C:\dir with space\file""")
                .springConfig("classpath:/application.yaml", "fake-service.yaml")
                .property("wins", "code")
                .waitForPort(port)
                .log(log)
                .startupTimeout(Duration.ofSeconds(30))
                .start()
        assertTrue(service.isAlive)
        assertTrue(LocalEnvironment.isListening("localhost", port))
        env.close()
        assertFalse(service.isAlive)
        val output = Files.readString(log)
        val config =
            Path
                .of(
                    javaClass.classLoader.getResource("fake-service.yaml")!!.toURI(),
                ).toAbsolutePath()
                .toString()
                .replace('\\', '/')
        assertTrue("listening on $port" in output, output)
        val springConfig = "--spring.config.location=classpath:/application.yaml,file:$config"
        assertTrue("""args=[$port] [--json=["YQ=="]] [C:\dir with space\file] [$springConfig] [--wins=code]""" in output, output)
        assertTrue("""greeting=[a "quoted" value]""" in output, output)

        val crash =
            assertFailsWith<IllegalStateException> {
                LocalEnvironment().use {
                    it
                        .service("crashing")
                        .classpath(System.getProperty("java.class.path"))
                        .mainClass(FakeService::class.java.name)
                        .args("not-a-port")
                        .waitForPort(port)
                        .log(log.resolveSibling("crashing.log"))
                        .start()
                }
            }
        assertTrue("exited with code" in crash.message!! && "NumberFormatException" in crash.message!!, crash.message)
    }

    @Test
    fun `a service that logs a startup failure but keeps running fails fast`() {
        val port = ServerSocket(0).use { it.localPort }
        val log = Files.createTempDirectory("loadtest").resolve("hanging.log")
        val started = System.nanoTime()
        val failure =
            assertFailsWith<LocalEnvironmentException> {
                LocalEnvironment().use {
                    it
                        .service("hanging")
                        .classpath(System.getProperty("java.class.path"))
                        .mainClass(HangingService::class.java.name)
                        .waitForPort(port)
                        .failOnOutput("Application run failed")
                        .log(log)
                        .start()
                }
            }
        assertTrue("logged \"Application run failed\"" in failure.message!!, failure.message)
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(20).toNanos(), "did not wait for the startup timeout")
    }
}

/** A stand-in service: prints what it was started with, then listens on the port given as its first argument until killed. */
object FakeService {
    @JvmStatic
    fun main(args: Array<String>) {
        println("args=" + args.joinToString(" ") { "[$it]" })
        println("greeting=[${System.getProperty("greeting")}]")
        ServerSocket(args[0].toInt()).use { socket ->
            println("listening on ${socket.localPort}")
            while (true) socket.accept().close()
        }
    }
}

/** A stand-in for a service whose startup failed but whose JVM stays up (non-daemon threads). */
object HangingService {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ERROR Application run failed")
        Thread.sleep(Long.MAX_VALUE)
    }
}
