package ua.gov.diia.loadtest.http

import ua.gov.diia.loadtest.Props
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where and how to connect, from the standard `-D` properties every HTTP load test takes:
 * `baseUrl` and `timeoutMs`.
 */
public data class HttpSettings
    @JvmOverloads
    constructor(
        /** `http(s)://host[:port][/prefix]`; paths of the scenario's requests are resolved against it. */
        public val baseUrl: String = "http://localhost:8080",
        public val timeoutMillis: Long = 5_000,
    ) {
        private val uri: URI = URI.create(baseUrl)

        init {
            require(uri.scheme == "http" || uri.scheme == "https") { "baseUrl must start with http:// or https://, got '$baseUrl'" }
            require(!uri.host.isNullOrEmpty()) { "baseUrl has no host: '$baseUrl'" }
            require(timeoutMillis > 0) { "the timeout must be positive, got $timeoutMillis ms" }
        }

        public val host: String get() = uri.host

        /** The port of [baseUrl], or the scheme's default one (80, 443). */
        public val port: Int
            get() =
                when {
                    uri.port != -1 -> uri.port
                    uri.scheme == "https" -> HTTPS_PORT
                    else -> HTTP_PORT
                }

        public val timeout: Duration get() = timeoutMillis.milliseconds

        public fun connect(): HttpTarget = HttpTarget.connect(this)

        override fun toString(): String = "$baseUrl timeout=${timeoutMillis}ms"

        public companion object {
            private const val HTTP_PORT = 80
            private const val HTTPS_PORT = 443

            @JvmStatic
            @JvmOverloads
            public fun fromSystemProperties(defaults: HttpSettings = HttpSettings()): HttpSettings =
                HttpSettings(
                    baseUrl = Props.get("baseUrl", defaults.baseUrl),
                    timeoutMillis = Props.getLong("timeoutMs", defaults.timeoutMillis),
                )
        }
    }
