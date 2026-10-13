package ua.gov.diia.loadtest.grpc

import ua.gov.diia.loadtest.Props
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where and how to connect, from the standard `-D` properties every load test takes:
 * `host`, `port`, `channels`, `tls`, `deadlineMs`.
 */
public data class GrpcSettings
    @JvmOverloads
    constructor(
        public val host: String = "localhost",
        public val port: Int = 9090,
        public val channels: Int = 1,
        public val tls: Boolean = false,
        public val deadlineMillis: Long = 5_000,
    ) {
        public val deadline: Duration get() = deadlineMillis.milliseconds

        public fun connect(): GrpcTarget = GrpcTarget.connect(host, port, channels, tls)

        override fun toString(): String = "$host:$port channels=$channels tls=$tls deadline=${deadlineMillis}ms"

        public companion object {
            @JvmStatic
            @JvmOverloads
            public fun fromSystemProperties(defaults: GrpcSettings = GrpcSettings()): GrpcSettings =
                GrpcSettings(
                    host = Props.get("host", defaults.host),
                    port = Props.getInt("port", defaults.port),
                    channels = Props.getInt("channels", defaults.channels),
                    tls = Props.getBoolean("tls", defaults.tls),
                    deadlineMillis = Props.getLong("deadlineMs", defaults.deadlineMillis),
                )
        }
    }
