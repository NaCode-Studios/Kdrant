package dev.kdrant.transport.grpc

import dev.kdrant.KdrantConfig
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import java.util.concurrent.TimeUnit

/**
 * Builds the gRPC channel a [KdrantConfig] describes.
 *
 * Qdrant serves gRPC on **6334**, not on the REST port, so a config carried over from the REST engine
 * needs its port changed. That is deliberate rather than corrected here: silently rewriting a port the
 * caller set is how a client ends up talking to something nobody meant it to.
 */
internal fun managedChannel(config: KdrantConfig): ManagedChannel {
    val builder = ManagedChannelBuilder.forAddress(config.host, config.port)
    if (config.useTls) builder.useTransportSecurity() else builder.usePlaintext()
    config.connectTimeout?.let { builder.keepAliveTimeout(it.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
    config.apiKey?.let { builder.intercept(ApiKeyInterceptor(it)) }
    return builder.build()
}

/**
 * Sends the API key as `api-key` metadata on every call, the same header name the REST engine uses.
 *
 * It is attached per call rather than per channel because gRPC metadata is per call: a channel-level
 * default would have to be copied into each one anyway, and doing it here means there is exactly one
 * place that decides whether a request carries credentials.
 */
internal class ApiKeyInterceptor(private val apiKey: String) : ClientInterceptor {

    override fun <Q : Any, S : Any> interceptCall(
        method: MethodDescriptor<Q, S>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<Q, S> = object : ForwardingClientCall.SimpleForwardingClientCall<Q, S>(
        next.newCall(method, callOptions),
    ) {
        override fun start(responseListener: Listener<S>, headers: Metadata) {
            headers.put(API_KEY, apiKey)
            super.start(responseListener, headers)
        }
    }

    internal companion object {
        val API_KEY: Metadata.Key<String> = Metadata.Key.of("api-key", Metadata.ASCII_STRING_MARSHALLER)
    }
}
