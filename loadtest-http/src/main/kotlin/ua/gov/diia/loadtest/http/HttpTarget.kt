package ua.gov.diia.loadtest.http

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The run's HTTP client and the service's base URL.
 *
 * Build requests with [request] and send them with [send] (Kotlin, suspending) or [sendBlocking] (Java: every
 * run of a Java scenario has its own virtual thread). Every request without a timeout of its own gets the
 * default one, so no request can hang a run forever. A response whose status is not expected fails with
 * [HttpStatusException], so the step is counted as failed (`HTTP_<status>` in the reports).
 *
 * HTTP/1.1 by default: the client opens as many connections as there are requests in flight and keeps them
 * alive, so behind a Kubernetes Service the load spreads over the replicas.
 */
public class HttpTarget(
    public val client: HttpClient,
    baseUrl: String,
    public val timeout: Duration,
) : AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val javaTimeout = timeout.toJavaDuration()

    public fun uri(path: String): URI =
        if (path.startsWith("http://") || path.startsWith("https://")) URI.create(path) else URI.create("$base/${path.trimStart('/')}")

    public fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(uri(path))

    /**
     * Sends [request] and returns the response with its body as a string. Without [expectedStatus] any 2xx is
     * expected; otherwise only the given codes (e.g. `200, 304` for a conditional GET).
     */
    public suspend fun send(
        request: HttpRequest,
        vararg expectedStatus: Int,
    ): HttpResponse<String> = client.sendAsync(withDefaultTimeout(request), BodyHandlers.ofString()).await().expect(expectedStatus)

    /** [send] for blocking code: Java scenarios, which run each run on its own virtual thread. */
    @Throws(IOException::class, InterruptedException::class)
    public fun sendBlocking(
        request: HttpRequest,
        vararg expectedStatus: Int,
    ): HttpResponse<String> = client.send(withDefaultTimeout(request), BodyHandlers.ofString()).expect(expectedStatus)

    private fun withDefaultTimeout(request: HttpRequest): HttpRequest =
        if (request.timeout().isPresent) request else HttpRequest.newBuilder(request) { _, _ -> true }.timeout(javaTimeout).build()

    private fun HttpResponse<String>.expect(expected: IntArray): HttpResponse<String> {
        val status = statusCode()
        val ok = if (expected.isEmpty()) status in SUCCESS else status in expected
        if (!ok) throw HttpStatusException(status, request().method(), uri(), body())
        return this
    }

    override fun close() {
        client.close()
    }

    public companion object {
        private val SUCCESS = 200..299

        /**
         * A client for [settings]: HTTP/1.1, no redirects followed (a redirect is a status the scenario should
         * see), [settings]' timeout as the connect timeout too. [configure] gets the builder last, for anything
         * not covered here (TLS context, HTTP/2, proxy, executor, ...).
         */
        @JvmStatic
        @JvmOverloads
        public fun connect(
            settings: HttpSettings,
            configure: HttpClient.Builder.() -> Unit = {},
        ): HttpTarget {
            val client =
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(settings.timeout.toJavaDuration())
                    .apply(configure)
                    .build()
            return HttpTarget(client, settings.baseUrl, settings.timeout)
        }
    }
}
