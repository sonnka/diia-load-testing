package ua.gov.diia.loadtest

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FailureDetailsTest {
    /** Shaped like a gRPC StatusRuntimeException: "CODE: description", the transport error as its cause. */
    private class StatusLikeException(
        message: String,
        cause: Throwable?,
    ) : RuntimeException(message, cause)

    @Test
    fun `a failure is described by its own message and its root cause, without repeating the code`() {
        val error = StatusLikeException("UNAVAILABLE: io exception", IOException("wrapper", IOException("Connection reset\n by peer")))
        assertEquals("io exception <- Connection reset by peer", describeFailure(error, "UNAVAILABLE"))
        assertEquals(
            "Connection refused",
            describeFailure(StatusLikeException("UNAVAILABLE", IOException("Connection refused")), "UNAVAILABLE"),
        )
        assertEquals("boom", describeFailure(IllegalStateException("boom"), "IllegalStateException"))
        val deadline =
            "DEADLINE_EXCEEDED: CallOptions deadline exceeded after 4.999997100s. Name resolution delay 0.000000000 seconds. " +
                "[closed=[CANCELLED], committed=[remote_addr=localhost/127.0.0.1:9090]]"
        assertEquals(
            "CallOptions deadline exceeded after #s. Name resolution delay # seconds. " +
                "[closed=[CANCELLED], committed=[remote_addr=localhost/127.0.0.1:9090]]",
            describeFailure(StatusLikeException(deadline, null), "DEADLINE_EXCEEDED"),
        )
        assertEquals("version 1.2.3, idx 211", describeFailure(IllegalStateException("version 1.2.3, idx 211"), "X"))
        assertEquals("(no message)", describeFailure(IllegalStateException(), "IllegalStateException"))
        assertEquals("NullPointerException", describeFailure(RuntimeException(null, NullPointerException()), "RuntimeException"))
    }

    @Test
    fun `reports count failures by code and message per phase, and cap the distinct messages`() =
        runBlocking {
            val result =
                executeLoadTest(
                    scenario("failing") {
                        step("call") {
                            if (runId % 2 == 0L) throw StatusLikeException("UNAVAILABLE: io exception", IOException("Connection reset"))
                            error("order $runId not found")
                        }
                    },
                    loadProfile { constant(40.0, 1.seconds) },
                    RunOptions(onProgress = {}, failureClassifier = { if (it is StatusLikeException) "UNAVAILABLE" else "NOT_FOUND" }),
                )
            val details = result.stats(step = "call").failureDetails
            assertEquals(FailureDetail("UNAVAILABLE", "io exception <- Connection reset", 20), details.first())
            val notFound = details.filter { it.code == "NOT_FOUND" }
            assertEquals(20L, notFound.sumOf { it.count })
            assertEquals(MAX_FAILURE_MESSAGES, notFound.size, notFound.joinToString("\n"))
            assertEquals(11L, notFound.single { it.message == OTHER_MESSAGES }.count)

            val files = result.writeReports(Files.createTempDirectory("loadtest"), title = "failures")
            val html = Files.readString(files.html)
            assertTrue("Failures (40 calls)" in html)
            assertTrue("io exception &lt;- Connection reset" in html)
            assertTrue("\"message\":\"io exception <- Connection reset\"" in Files.readString(files.json))
            assertTrue("20 x UNAVAILABLE: io exception <- Connection reset" in ConsoleReport.render(result))
        }
}
