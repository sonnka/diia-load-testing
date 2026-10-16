package ua.gov.diia.loadtest.http

import ua.gov.diia.loadtest.FailureClassifier
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/**
 * A response with a status the scenario did not expect; reported as `HTTP_<status>`. Throw it from a step to
 * fail it on a status [HttpTarget.send] accepted, e.g. after checking the body.
 */
public class HttpStatusException(
    public val status: Int,
    public val method: String,
    public val uri: URI,
    public val body: String,
) : RuntimeException(
        // No URI in the message: the reports group failures by message, and paths often carry ids.
        "HTTP $status" + body.trim().take(MAX_BODY_IN_MESSAGE).let { if (it.isEmpty()) "" else ": $it" },
    ) {
    private companion object {
        const val MAX_BODY_IN_MESSAGE = 200
    }
}

/**
 * Reports a failed request as `HTTP_<status>` for an unexpected status, `TIMEOUT` and `CONNECT_TIMEOUT` for
 * timeouts and `CONNECTION_REFUSED` when nothing accepts the connection; anything else by its class name.
 */
public object HttpFailureClassifier : FailureClassifier {
    override fun classify(error: Throwable): String =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .firstNotNullOfOrNull {
                when (it) {
                    is HttpStatusException -> "HTTP_${it.status}"
                    is HttpConnectTimeoutException -> "CONNECT_TIMEOUT"
                    is HttpTimeoutException -> "TIMEOUT"
                    is ConnectException -> "CONNECTION_REFUSED"
                    else -> null
                }
            } ?: FailureClassifier.Default.classify(error)

    private const val MAX_CAUSE_DEPTH = 16
}
