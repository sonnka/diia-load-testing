@file:JvmName("GrpcStubs")

package ua.gov.diia.loadtest.grpc

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.kotlin.ClientCalls
import io.grpc.stub.AbstractStub
import io.grpc.stub.MetadataUtils
import ua.gov.diia.loadtest.FailureClassifier
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Gives every call without a deadline of its own one of [timeoutMillis], counted from the moment that call
 * starts - so no call can hang a run forever, and scenarios need no per-call `withDeadline`. A deadline set
 * on the stub (`withDeadline`, `withDeadlineAfter`) still wins. [GrpcLoadTestDefinition.stubs] installs it
 * with `deadlineMs`; elsewhere: `factory(ClientInterceptors.intercept(channel, DefaultDeadline(5_000)))`.
 */
public class DefaultDeadline(
    private val timeoutMillis: Long,
) : ClientInterceptor {
    init {
        require(timeoutMillis > 0) { "the deadline must be positive, got $timeoutMillis ms" }
    }

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        next.newCall(
            method,
            if (callOptions.deadline != null) callOptions else callOptions.withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS),
        )
}

/**
 * A deadline [timeout] from *now*, for a call that needs a different one than the default. gRPC deadlines are absolute, so derive the stub right before
 * each call (`stub.withDeadline(5.seconds).getChallenge(request)`), never once per test.
 */
public fun <S : AbstractStub<S>> S.withDeadline(timeout: Duration): S =
    withDeadlineAfter(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

/**
 * Makes a unary call to [method] through this stub's channel and call options (deadline, headers,
 * interceptors), suspending until the response arrives - so services whose protos only generate Java
 * stubs (no grpc-kotlin) are load-tested the same way:
 * `stub.withDeadline(5.seconds).unaryCall(TestListGrpc.getTakeIndexMethod(), request)`.
 * Fails with [io.grpc.StatusException] like a coroutine stub would.
 */
public suspend fun <S : AbstractStub<S>, ReqT, RespT> S.unaryCall(
    method: MethodDescriptor<ReqT, RespT>,
    request: ReqT,
): RespT = ClientCalls.unaryRpc(channel, method, request, callOptions)

/** Sends [headers] with every call made through the returned stub - e.g. a per-run session/device identity. */
public fun <S : AbstractStub<S>> S.withHeaders(headers: Metadata): S = withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))

/** [metadataOf] from a map, e.g. `GrpcStubs.metadataOf(Map.of("x-api-key", apiKey))` in Java. */
public fun metadataOf(headers: Map<String, String>): Metadata = metadataOf(*headers.map { it.key to it.value }.toTypedArray())

/** ASCII headers. For binary (`-bin`) headers build the [Metadata] directly with a binary marshaller. */
public fun metadataOf(vararg headers: Pair<String, String>): Metadata =
    Metadata().apply {
        headers.forEach { (name, value) ->
            require(!name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) { "'$name' is a binary header; put it into Metadata directly" }
            put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
        }
    }

/** Reports a failed call by its gRPC status code name (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, ...). */
public object GrpcFailureClassifier : FailureClassifier {
    override fun classify(error: Throwable): String = statusOf(error)?.code?.name ?: FailureClassifier.Default.classify(error)

    public fun statusOf(error: Throwable): Status? =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .firstNotNullOfOrNull {
                when (it) {
                    is StatusException -> it.status
                    is StatusRuntimeException -> it.status
                    else -> null
                }
            }

    private const val MAX_CAUSE_DEPTH = 16
}
