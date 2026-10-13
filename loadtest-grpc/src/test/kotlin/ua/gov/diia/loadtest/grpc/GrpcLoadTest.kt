package ua.gov.diia.loadtest.grpc

import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ua.gov.diia.loadtest.LoadTestContext
import ua.gov.diia.loadtest.LoadTestFailedException
import ua.gov.diia.loadtest.LoadTestMain
import ua.gov.diia.loadtest.LoadTestResult
import ua.gov.diia.loadtest.Phases
import ua.gov.diia.loadtest.RunOptions
import ua.gov.diia.loadtest.executeLoadTest
import ua.gov.diia.loadtest.loadProfile
import ua.gov.diia.loadtest.scenario
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GrpcLoadTest {
    private val serverName = InProcessServerBuilder.generateName()
    private val health = HealthStatusManager()
    private val seenRunIds = ConcurrentHashMap.newKeySet<String>()
    private val runIdHeader = Metadata.Key.of("x-run-id", Metadata.ASCII_STRING_MARSHALLER)
    private val delayHeader = Metadata.Key.of("x-delay-ms", Metadata.ASCII_STRING_MARSHALLER)
    private val server =
        InProcessServerBuilder
            .forName(serverName)
            .addService(
                ServerInterceptors.intercept(
                    health.healthService,
                    object : io.grpc.ServerInterceptor {
                        override fun <ReqT, RespT> interceptCall(
                            call: io.grpc.ServerCall<ReqT, RespT>,
                            headers: Metadata,
                            next: io.grpc.ServerCallHandler<ReqT, RespT>,
                        ): io.grpc.ServerCall.Listener<ReqT> {
                            headers.get(runIdHeader)?.let(seenRunIds::add)
                            headers.get(delayHeader)?.let { Thread.sleep(it.toLong()) } // a slow server
                            return next.startCall(call, headers)
                        }
                    },
                ),
            ).build()
    private lateinit var target: GrpcTarget

    @BeforeTest
    fun start() {
        server.start()
        health.setStatus("ok", ServingStatus.SERVING)
        target = GrpcTarget(List(3) { InProcessChannelBuilder.forName(serverName).build() })
    }

    @AfterTest
    fun stop() {
        target.close()
        server.shutdownNow()
    }

    @Test
    fun `runs a gRPC scenario over the channel pool, classifying failures by status code`() =
        runBlocking {
            val stubs = target.stubs { HealthGrpc.newStub(it) }
            val result =
                executeLoadTest(
                    scenario("health") {
                        val stub = stubs().withHeaders(metadataOf("x-run-id" to runId.toString())).withDeadline(2.seconds)
                        step("check ok") { stub.unaryCall(HealthGrpc.getCheckMethod(), request("ok")) }
                        step("check missing") { stub.unaryCall(HealthGrpc.getCheckMethod(), request("missing")) }
                    },
                    loadProfile { constant(30.0, 1.seconds) },
                    RunOptions(failureClassifier = GrpcFailureClassifier, onProgress = {}),
                )
            assertEquals(30L, result.stats(step = "check ok").ok)
            assertEquals(mapOf("NOT_FOUND" to 30L), result.stats(step = "check missing").failures)
            assertEquals(3, target.size)
        }

    @Test
    fun `classifier finds a status anywhere in the cause chain`() {
        assertEquals("UNAVAILABLE", GrpcFailureClassifier.classify(Status.UNAVAILABLE.asRuntimeException()))
        assertEquals("NOT_FOUND", GrpcFailureClassifier.classify(IllegalStateException("wrapped", StatusException(Status.NOT_FOUND))))
        assertEquals("IllegalArgumentException", GrpcFailureClassifier.classify(IllegalArgumentException()))
    }

    @Test
    fun `json survives a round trip through Struct`() {
        val json = Json.parseToJsonElement("""{"kty":"EC","n":1,"f":1.5,"b":true,"z":null,"a":[{"x":"y"}]}""").jsonObject
        assertEquals(json, json.toStruct().toJsonObject())
    }

    @Test
    fun `headers reach the server`() =
        runBlocking {
            val stub = target.stubs { HealthGrpc.newStub(it) }().withHeaders(metadataOf("x-run-id" to "42"))
            stub.unaryCall(HealthGrpc.getCheckMethod(), request("ok"))
            assertTrue("42" in seenRunIds)
        }

    @Test
    fun `a gRPC definition connects after beforeAll, classifies by status and closes the connection`() {
        val events = mutableListOf<String>()
        val definition =
            object : GrpcLoadTestDefinition("health load test") {
                init {
                    addScenario("check", "Health check") {
                        val stubs = stubs { HealthGrpc.newStub(it) }
                        scenario("health") {
                            step("check missing") { stubs().unaryCall(HealthGrpc.getCheckMethod(), request("missing")) }
                        }
                    }
                }

                override fun profile() = loadProfile { constant(20.0, 1.seconds, Phases.STEADY) }

                override fun connect(settings: GrpcSettings): GrpcTarget {
                    events += "connect ${settings.port}"
                    return GrpcTarget(listOf(InProcessChannelBuilder.forName(serverName).build()))
                }

                override fun beforeAll(context: LoadTestContext) {
                    events += "beforeAll"
                }

                override fun afterAll(
                    context: LoadTestContext,
                    result: LoadTestResult,
                ) {
                    events += "failures ${result.stats(step = "check missing").failures}"
                }
            }
        System.setProperty("port", "7777")
        try {
            val failure = assertFailsWith<LoadTestFailedException> { LoadTestMain.run(definition) }
            assertTrue(failure.verdict.results.any { !it.passed && "error rate" in it.description })
        } finally {
            System.clearProperty("port")
        }
        assertEquals(listOf("beforeAll", "connect 7777", "failures {NOT_FOUND=20}"), events)
        assertEquals(5_000L, definition.deadlineMillis)
    }

    @Test
    fun `deadlineMs applies to every call without the scenario setting one`() {
        val definition =
            object : GrpcLoadTestDefinition("deadline test") {
                init {
                    addScenario("slow", "A call the server answers after a second") {
                        val stubs = stubs { HealthGrpc.newStub(it) }
                        scenario("slow") {
                            step("slow check") {
                                stubs()
                                    .withHeaders(
                                        metadataOf("x-delay-ms" to "1000"),
                                    ).unaryCall(HealthGrpc.getCheckMethod(), request("ok"))
                            }
                        }
                    }
                }

                override fun profile() = loadProfile { constant(5.0, 1.seconds, Phases.STEADY) }

                override fun connect(settings: GrpcSettings) = GrpcTarget(listOf(InProcessChannelBuilder.forName(serverName).build()))

                override fun afterAll(
                    context: LoadTestContext,
                    result: LoadTestResult,
                ) {
                    assertEquals(mapOf("DEADLINE_EXCEEDED" to 5L), result.stats(step = "slow check").failures)
                }
            }
        System.setProperty("deadlineMs", "100")
        try {
            assertFailsWith<LoadTestFailedException> { LoadTestMain.run(definition) }
        } finally {
            System.clearProperty("deadlineMs")
        }
    }

    private fun request(service: String) = HealthCheckRequest.newBuilder().setService(service).build()
}
