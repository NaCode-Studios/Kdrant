package dev.kdrant.transport.grpc

import dev.kdrant.KdrantException
import grpc.health.v1.HealthCheck.HealthCheckRequest
import grpc.health.v1.HealthCheck.HealthCheckResponse
import grpc.health.v1.HealthGrpcKt
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M39 over gRPC: the token on the wire, and `PERMISSION_DENIED` arriving as the same type HTTP 403
 * raises on the REST engine.
 *
 * Like [ApiKeyInterceptorTest], the exchange is real and in-process, so what is asserted is the
 * metadata the server received rather than the interceptor's own bookkeeping.
 */
class BearerTokenInterceptorTest {

    private class RecordingHealth : HealthGrpcKt.HealthCoroutineImplBase() {
        override suspend fun check(request: HealthCheckRequest): HealthCheckResponse =
            HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                .build()
    }

    private class HeaderRecorder : ServerInterceptor {
        val authorization = mutableListOf<String?>()
        val apiKey = mutableListOf<String?>()

        override fun <Q : Any, S : Any> interceptCall(
            call: ServerCall<Q, S>,
            headers: Metadata,
            next: ServerCallHandler<Q, S>,
        ): ServerCall.Listener<Q> {
            authorization += headers.get(BearerTokenInterceptor.AUTHORIZATION)
            apiKey += headers.get(ApiKeyInterceptor.API_KEY)
            return next.startCall(call, headers)
        }
    }

    private fun exchange(token: String?): HeaderRecorder {
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
            .let { if (token != null) it.intercept(BearerTokenInterceptor(token)) else it }
            .build()
        try {
            val response = runBlocking {
                HealthGrpcKt.HealthCoroutineStub(channel).check(HealthCheckRequest.getDefaultInstance())
            }
            assertEquals(HealthCheckResponse.ServingStatus.SERVING, response.status)
            return recorder
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `the token reaches the server as an Authorization bearer, with no api-key beside it`() {
        val recorder = exchange("a.jwt.value")

        assertEquals(listOf("Bearer a.jwt.value"), recorder.authorization)
        assertNull(recorder.apiKey.single())
    }

    @Test
    fun `no token means no header, not an empty one`() {
        assertNull(exchange(null).authorization.single())
    }

    @Test
    fun `PERMISSION_DENIED is a Forbidden naming the collection`() {
        val error = runBlocking {
            runCatching {
                GrpcErrors.mapping("docs") {
                    throw StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Write access denied"))
                }
            }.exceptionOrNull()
        }

        assertTrue(error is KdrantException.Forbidden, "expected Forbidden, got ${error?.let { it::class }}")
        assertEquals("docs", (error as KdrantException.Forbidden).collection)
        assertTrue(error.message!!.contains("Write access denied"))
    }

    @Test
    fun `UNAUTHENTICATED stays Unauthorized`() {
        val error = runBlocking {
            runCatching {
                GrpcErrors.mapping(null) {
                    throw StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Must provide an API key"))
                }
            }.exceptionOrNull()
        }

        assertTrue(error is KdrantException.Unauthorized)
        assertFalse(error is KdrantException.Forbidden)
    }
}
