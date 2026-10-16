package ua.gov.diia.loadtest.http

import ua.gov.diia.loadtest.FailureClassifier
import ua.gov.diia.loadtest.LoadTestDefinition
import ua.gov.diia.loadtest.Parameter
import ua.gov.diia.loadtest.local.LocalEnvironment
import ua.gov.diia.loadtest.local.LocalEnvironmentException
import ua.gov.diia.loadtest.local.ServiceLauncher

/**
 * A [LoadTestDefinition] for an HTTP service: adds the connection parameters (`baseUrl`, `timeoutMs`), opens
 * the client on first use - after `beforeAll`, so a locally started service is already up - closes it after
 * the run, and reports failures by HTTP status ([HttpFailureClassifier]).
 */
public abstract class HttpLoadTestDefinition
    @JvmOverloads
    constructor(
        title: String,
        description: String = "",
    ) : LoadTestDefinition(title, description) {
        private val lock = Any()
        private var target: HttpTarget? = null

        public val baseUrl: Parameter<String> =
            stringParam("baseUrl", DEFAULTS.baseUrl, "Base URL of the service; request paths are resolved against it")
        public val timeoutMs: Parameter<Long> =
            longParam("timeoutMs", DEFAULTS.timeoutMillis, "Timeout of each request, ms; applied to every request without its own")

        public val http: HttpSettings
            get() = HttpSettings(baseUrl.value, timeoutMs.value)

        public fun target(): HttpTarget =
            synchronized(lock) {
                target ?: connect(http).also { opened ->
                    target = opened
                    println("HTTP target: $http")
                    context.closeLater(
                        AutoCloseable {
                            synchronized(lock) { target = null }
                            opened.close()
                        },
                    )
                }
            }

        /**
         * Opens the client; override for a custom one (TLS trust, HTTP/2, ...), e.g.
         * `HttpTarget.connect(settings) { sslContext(...) }`. Fails at once when nothing listens on the base
         * URL's host and port, instead of a whole run of `CONNECTION_REFUSED`.
         */
        public open fun connect(settings: HttpSettings): HttpTarget {
            if (!LocalEnvironment.isListening(settings.host, settings.port)) {
                val hint =
                    if (context.isLocal) {
                        "the service did not start"
                    } else {
                        "start the service first, or run with -Dlocal=true if this load test starts it itself"
                    }
                throw LocalEnvironmentException("Nothing is listening on ${settings.host}:${settings.port}: $hint")
            }
            return settings.connect()
        }

        /**
         * The project's Spring Boot service with its HTTP server (`server.port`) on the port of this test's
         * `baseUrl`, started once that port answers; for [beforeAll] in local mode. Add the service's own
         * settings and [ServiceLauncher.start] it.
         */
        protected fun springHttpService(name: String): ServiceLauncher =
            context.environment
                .springBootService(name)
                .property("server.port", http.port)
                .waitForPort(http.port)

        override fun failureClassifier(): FailureClassifier = HttpFailureClassifier

        private companion object {
            val DEFAULTS = HttpSettings()
        }
    }
