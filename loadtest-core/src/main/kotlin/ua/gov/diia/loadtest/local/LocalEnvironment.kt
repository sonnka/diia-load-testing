package ua.gov.diia.loadtest.local

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What a load test against a local instance needs around it, in plain JVM code instead of shell
 * scripts: checks that the real dependencies are up, fake HTTP dependencies, and the service itself
 * as a child process. Get it from `LoadTestContext.environment` in `beforeAll`; everything it starts
 * is stopped after the run (in reverse order), also on failure or Ctrl+C.
 */
public class LocalEnvironment internal constructor() : AutoCloseable {
    private val started = ArrayDeque<AutoCloseable>()

    /** Fails with a readable message unless something accepts TCP connections on [host]:[port]. */
    @JvmOverloads
    public fun requireListening(
        name: String,
        host: String,
        port: Int,
        hint: String = "start it first",
    ): LocalEnvironment =
        apply {
            if (!isListening(host, port)) throw LocalEnvironmentException("$name is not reachable at $host:$port - $hint")
        }

    /** Fails unless [port] on localhost is free, e.g. so a stale instance is not load-tested by mistake. */
    public fun requireFree(
        name: String,
        port: Int,
    ): LocalEnvironment =
        apply {
            if (isListening(LOCALHOST, port)) {
                throw LocalEnvironmentException(
                    "Port $port ($name) is already in use on localhost - stop whatever runs there or pick another port",
                )
            }
        }

    /** Starts a [FakeHttpServer] on [port]; 0 (the default) picks a free one, see [FakeHttpServer.port]. */
    @JvmOverloads
    public fun fakeHttp(port: Int = 0): FakeHttpServer = track(FakeHttpServer(port))

    /** Describes a service process to start; see [ServiceLauncher.start]. */
    public fun service(name: String): ServiceLauncher = ServiceLauncher(name) { track(it) }

    /** [service] for a Spring Boot application: its start fails as soon as it logs `Application run failed`. */
    public fun springBootService(name: String): ServiceLauncher = service(name).failOnOutput("Application run failed")

    @Synchronized
    private fun <T : AutoCloseable> track(resource: T): T = resource.also { started.addLast(it) }

    @Synchronized
    override fun close() {
        var failure: Exception? = null
        while (started.isNotEmpty()) {
            try {
                started.removeLast().close()
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }

    public companion object {
        internal const val LOCALHOST = "localhost"
        private const val CONNECT_TIMEOUT_MS = 1_000

        /** True if something accepts TCP connections on [host]:[port] within a second. */
        @JvmStatic
        public fun isListening(
            host: String,
            port: Int,
        ): Boolean =
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
                true
            } catch (e: IOException) {
                false
            }
    }
}

/**
 * The local environment is not what the load test needs: a dependency is down, a port is taken, the service
 * did not start. The runner prints just the message - it says what to fix - without a stack trace.
 */
public class LocalEnvironmentException(
    message: String,
) : IllegalStateException(message)
