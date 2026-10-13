package ua.gov.diia.loadtest.local

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Answers one request to a [FakeHttpServer] route. */
public fun interface FakeHandler {
    @Throws(Exception::class)
    public fun handle(request: FakeRequest): FakeResponse
}

public class FakeRequest internal constructor(
    public val method: String,
    public val path: String,
    public val query: String?,
    private val headers: Map<String, List<String>>,
    public val body: String,
) {
    /** First value of header [name] (case-insensitive), or null. */
    public fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

public class FakeResponse private constructor(
    public val status: Int,
    public val body: String,
    public val contentType: String?,
) {
    public companion object {
        private const val OK = 200

        @JvmStatic
        @JvmOverloads
        public fun json(
            body: String,
            status: Int = OK,
        ): FakeResponse = FakeResponse(status, body, "application/json")

        @JvmStatic
        @JvmOverloads
        public fun text(
            body: String,
            status: Int = OK,
        ): FakeResponse = FakeResponse(status, body, "text/plain; charset=utf-8")

        /** An empty response with [status]. */
        @JvmStatic
        @JvmOverloads
        public fun status(status: Int = OK): FakeResponse = FakeResponse(status, "", null)
    }
}

/**
 * A stand-in for an HTTP dependency of the service under test, on localhost, so the load test
 * measures the service and not its dependencies. Handlers run one virtual thread per request, so the
 * fake never becomes the bottleneck. A path ending in `/{*}` matches every path with that prefix;
 * unknown routes answer 404 and are counted in [unmatched].
 */
public class FakeHttpServer internal constructor(
    port: Int,
) : AutoCloseable {
    private val routes = ConcurrentHashMap<String, FakeHandler>()
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val unmatchedCount = AtomicLong()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG).apply {
            executor = this@FakeHttpServer.executor
            createContext("/") { exchange ->
                try {
                    serve(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    public val port: Int get() = server.address.port

    /** `http://localhost:<port>`, to hand to the service under test. */
    public val baseUrl: String get() = "http://${LocalEnvironment.LOCALHOST}:$port"

    /** Requests that matched no route. */
    public val unmatched: Long get() = unmatchedCount.get()

    public fun route(
        method: String,
        path: String,
        handler: FakeHandler,
    ): FakeHttpServer =
        apply {
            val key = key(method, path)
            routes[key] = handler
            counts.putIfAbsent(key, AtomicLong())
        }

    public fun get(
        path: String,
        handler: FakeHandler,
    ): FakeHttpServer = route("GET", path, handler)

    public fun post(
        path: String,
        handler: FakeHandler,
    ): FakeHttpServer = route("POST", path, handler)

    public fun put(
        path: String,
        handler: FakeHandler,
    ): FakeHttpServer = route("PUT", path, handler)

    public fun requests(
        method: String,
        path: String,
    ): Long = counts[key(method, path)]?.get() ?: 0

    private fun serve(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path
        val key = key(method, path).takeIf { routes.containsKey(it) } ?: prefixRoute(method, path)
        val response =
            if (key == null) {
                unmatchedCount.incrementAndGet()
                FakeResponse.status(NOT_FOUND)
            } else {
                counts.getValue(key).incrementAndGet()
                val request =
                    FakeRequest(
                        method,
                        path,
                        exchange.requestURI.rawQuery,
                        exchange.requestHeaders,
                        exchange.requestBody.readAllBytes().decodeToString(),
                    )
                try {
                    routes.getValue(key).handle(request)
                } catch (e: Exception) {
                    FakeResponse.text(e.toString(), INTERNAL_ERROR)
                }
            }
        val bytes = response.body.encodeToByteArray()
        response.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun prefixRoute(
        method: String,
        path: String,
    ): String? {
        val methodPrefix = "$method "
        return routes.keys
            .filter { it.startsWith(methodPrefix) && it.endsWith(WILDCARD) }
            .filter { path.startsWith(it.removePrefix(methodPrefix).removeSuffix(ANY)) }
            .maxByOrNull { it.length }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    override fun toString(): String = "FakeHttpServer($baseUrl, routes=${routes.keys})"

    private companion object {
        const val BACKLOG = 1_024
        const val NOT_FOUND = 404
        const val INTERNAL_ERROR = 500
        const val ANY = "{*}"
        const val WILDCARD = "/$ANY"

        fun key(
            method: String,
            path: String,
        ) = "${method.uppercase()} $path"
    }
}
