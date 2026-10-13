package ua.gov.diia.loadtest.grpc

import io.grpc.Channel
import io.grpc.ClientInterceptors
import io.grpc.stub.AbstractStub
import ua.gov.diia.loadtest.FailureClassifier
import ua.gov.diia.loadtest.LoadTestDefinition
import ua.gov.diia.loadtest.Parameter
import ua.gov.diia.loadtest.local.LocalEnvironment
import ua.gov.diia.loadtest.local.LocalEnvironmentException
import ua.gov.diia.loadtest.local.ServiceLauncher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [LoadTestDefinition] for a gRPC service: adds the connection parameters (`host`, `port`,
 * `channels`, `tls`, `deadlineMs`), opens the connection on first use - after `beforeAll`, so a
 * locally started service is already up - closes it after the run, and reports failures by gRPC
 * status code.
 */
public abstract class GrpcLoadTestDefinition
    @JvmOverloads
    constructor(
        title: String,
        description: String = "",
    ) : LoadTestDefinition(title, description) {
        private val lock = Any()
        private var target: GrpcTarget? = null

        public val host: Parameter<String> = stringParam("host", DEFAULTS.host, "gRPC host")
        public val port: Parameter<Int> = intParam("port", DEFAULTS.port, "gRPC port")
        public val channels: Parameter<Int> =
            intParam("channels", DEFAULTS.channels, "gRPC connections used round-robin; behind a k8s Service: at least the replica count")
        public val tls: Parameter<Boolean> = booleanParam("tls", DEFAULTS.tls, "Connect with TLS")
        public val deadlineMs: Parameter<Long> =
            longParam("deadlineMs", DEFAULTS.deadlineMillis, "Deadline of each call, ms; applied to every call automatically")

        public val grpc: GrpcSettings
            get() = GrpcSettings(host.value, port.value, channels.value, tls.value, deadlineMs.value)

        public val deadline: Duration get() = deadlineMs.value.milliseconds

        public val deadlineMillis: Long get() = deadlineMs.value

        /** The run's connection: opened on first use, closed after the run. */
        public fun target(): GrpcTarget =
            synchronized(lock) {
                target ?: connect(grpc).also { opened ->
                    target = opened
                    println("gRPC target: $grpc")
                    context.closeLater(
                        AutoCloseable {
                            synchronized(lock) { target = null }
                            opened.close()
                        },
                    )
                }
            }

        /**
         * Stubs over the run's connection, one per channel, handed out round-robin. Every call made through
         * them gets the `deadlineMs` deadline ([DefaultDeadline]) unless the stub sets its own.
         */
        public fun <S : AbstractStub<S>> stubs(factory: (Channel) -> S): StubPool<S> {
            val defaultDeadline = DefaultDeadline(deadlineMs.value)
            return target().stubs { channel -> factory(ClientInterceptors.intercept(channel, defaultDeadline)) }
        }

        /**
         * Opens the connection; override for custom channels (interceptors, TLS trust, in-process tests).
         * Fails at once when nothing listens on `host:port`, instead of a whole run of `UNAVAILABLE`.
         */
        public open fun connect(settings: GrpcSettings): GrpcTarget {
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
         * The project's Spring Boot service with its gRPC server on this test's `port`, started once that port
         * answers; for [beforeAll] in local mode. Add the service's own settings and [ServiceLauncher.start] it:
         */
        protected fun springGrpcService(name: String): ServiceLauncher =
            context.environment
                .springBootService(name)
                .property("spring.grpc.server.port", port.value)
                .waitForPort(port.value)

        override fun failureClassifier(): FailureClassifier = GrpcFailureClassifier

        private companion object {
            val DEFAULTS = GrpcSettings()
        }
    }
