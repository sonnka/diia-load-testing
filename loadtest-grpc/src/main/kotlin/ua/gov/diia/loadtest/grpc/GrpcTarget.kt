package ua.gov.diia.loadtest.grpc

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.AbstractStub
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pool of [ManagedChannel]s to one target, used round-robin.
 *
 * One channel is one HTTP/2 connection. Behind a Kubernetes Service (L4 balancing) a single
 * connection sticks to one pod, so a load test with one channel only ever loads one replica; open
 * as many channels as there are replicas (or more), or point [forTarget] at a headless service with
 * `loadBalancingPolicy = "round_robin"`.
 */
public class GrpcTarget(
    channels: List<ManagedChannel>,
) : AutoCloseable {
    private val channels: List<ManagedChannel> = channels.toList()
    private val next = AtomicInteger()

    init {
        require(this.channels.isNotEmpty()) { "at least one channel is required" }
    }

    public val size: Int get() = channels.size

    public fun channel(): ManagedChannel = channels[Math.floorMod(next.getAndIncrement(), channels.size)]

    /**
     * Builds one stub per channel and hands them out round-robin - take one per run (`stubs()` in
     * Kotlin, `stubs.next()` in Java), then derive per-call variants (deadline, headers) from it.
     */
    public fun <S : AbstractStub<S>> stubs(factory: (Channel) -> S): StubPool<S> {
        val pool = channels.map(factory)
        val index = AtomicInteger()
        return StubPool { pool[Math.floorMod(index.getAndIncrement(), pool.size)] }
    }

    override fun close() {
        channels.forEach { it.shutdown() }
        channels.forEach { if (!it.awaitTermination(5, TimeUnit.SECONDS)) it.shutdownNow() }
    }

    public companion object {
        /**
         * Opens [channels] channels to [target] (`host:port`, or any gRPC target URI such as
         * `dns:///test-service:9090`). [configure] gets each builder last, for anything
         * not covered here (message size limits, keep-alive, user agent, ...).
         */
        @JvmStatic
        @JvmOverloads
        public fun forTarget(
            target: String,
            channels: Int = 1,
            tls: Boolean = false,
            loadBalancingPolicy: String? = null,
            configure: ManagedChannelBuilder<*>.() -> Unit = {},
        ): GrpcTarget {
            require(channels > 0) { "channels must be > 0" }
            return GrpcTarget(
                List(channels) {
                    val builder = ManagedChannelBuilder.forTarget(target)
                    if (tls) builder.useTransportSecurity() else builder.usePlaintext()
                    loadBalancingPolicy?.let { builder.defaultLoadBalancingPolicy(it) }
                    builder.configure()
                    builder.build()
                },
            )
        }

        @JvmStatic
        @JvmOverloads
        public fun connect(
            host: String,
            port: Int,
            channels: Int = 1,
            tls: Boolean = false,
            configure: ManagedChannelBuilder<*>.() -> Unit = {},
        ): GrpcTarget = forTarget("$host:$port", channels, tls, configure = configure)
    }
}

public fun interface StubPool<S> {
    public fun next(): S

    public operator fun invoke(): S = next()
}
