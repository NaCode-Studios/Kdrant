package dev.kdrant.transport.grpc

import grpc.health.v1.HealthCheck.HealthCheckRequest
import grpc.health.v1.HealthCheck.HealthCheckResponse
import grpc.health.v1.HealthGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Runs a real gRPC exchange over the in-process transport, so what is asserted is the metadata the
 * server actually received rather than the interceptor's own bookkeeping.
 */
class ApiKeyInterceptorTest {

    /** A Health service that answers SERVING and records the headers each call arrived with. */
    private class RecordingHealth : HealthGrpcKt.HealthCoroutineImplBase() {
        override suspend fun check(request: HealthCheckRequest): HealthCheckResponse =
            HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                .build()
    }

    private class HeaderRecorder : ServerInterceptor {
        val seen = mutableListOf<String?>()

        override fun <Q : Any, S : Any> interceptCall(
            call: ServerCall<Q, S>,
            headers: Metadata,
            next: ServerCallHandler<Q, S>,
        ): ServerCall.Listener<Q> {
            seen += headers.get(ApiKeyInterceptor.API_KEY)
            return next.startCall(call, headers)
        }
    }

    private fun exchange(apiKey: String?): List<String?> {
        val name = InProcessServerBuilder.generateName()
        val recorder = HeaderRecorder()
        val server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(RecordingHealth())
            .intercept(recorder)
            .build()
            .start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name)
            .directExecutor()
            .let { if (apiKey != null) it.intercept(ApiKeyInterceptor(apiKey)) else it }
            .build()
        try {
            val response = runBlocking {
                HealthGrpcKt.HealthCoroutineStub(channel).check(HealthCheckRequest.getDefaultInstance())
            }
            assertEquals(HealthCheckResponse.ServingStatus.SERVING, response.status)
            return recorder.seen.toList()
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `the api key reaches the server as api-key metadata`() {
        assertEquals(listOf("SUPER-SECRET-KEY"), exchange("SUPER-SECRET-KEY"))
    }

    @Test
    fun `no api key means no header, not an empty one`() {
        assertNull(exchange(null).single())
    }
}
