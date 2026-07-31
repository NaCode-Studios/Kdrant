package dev.kdrant.transport.grpc

import dev.kdrant.KdrantException
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * gRPC statuses onto the same exception hierarchy the REST engine raises. A caller that switches
 * engines keeps its `when`, which only holds if the two agree on which failure is which.
 */
class GrpcErrorsTest {

    @Test
    fun `NOT_FOUND on a collection call names the collection`() = runTest {
        val error = assertThrows<KdrantException.CollectionNotFound> {
            GrpcErrors.mapping("docs") { throw Status.NOT_FOUND.withDescription("nope").asRuntimeException() }
        }

        assertEquals("docs", error.collection)
        assertTrue(error.message!!.contains("nope"), error.message)
    }

    @Test
    fun `NOT_FOUND with no collection in scope is a bad request, not a missing collection`() = runTest {
        // listAliases and the storage snapshots are not about a collection, so there is no name to
        // report and inventing one would be worse than saying the request was rejected.
        assertThrows<KdrantException.InvalidRequest> {
            GrpcErrors.mapping(null) { throw Status.NOT_FOUND.asRuntimeException() }
        }
    }

    @Test
    fun `INVALID_ARGUMENT saying the collection does not exist is read as a missing collection`() = runTest {
        // Qdrant answers some write paths this way rather than with NOT_FOUND, and the only thing
        // separating it from a genuinely malformed request is the text.
        val error = assertThrows<KdrantException.CollectionNotFound> {
            GrpcErrors.mapping("docs") {
                throw Status.INVALID_ARGUMENT.withDescription("Collection `docs` doesn't exist!").asRuntimeException()
            }
        }

        assertEquals("docs", error.collection)
    }

    @Test
    fun `INVALID_ARGUMENT about anything else stays a bad request`() = runTest {
        assertThrows<KdrantException.InvalidRequest> {
            GrpcErrors.mapping("docs") {
                throw Status.INVALID_ARGUMENT.withDescription("Wrong input: vector dim mismatch").asRuntimeException()
            }
        }
    }

    @Test
    fun `a cancellation propagates untouched, so structured concurrency still works`() = runTest {
        // gRPC reports a cancelled call as Status.CANCELLED; turning that into a KdrantException would
        // hand the coroutine a failure instead of its own cancellation.
        assertThrows<CancellationException> {
            GrpcErrors.mapping("docs") { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `the remaining statuses land on the exception the REST engine raises for the same case`() = runTest {
        val cases = listOf<Pair<Status, Class<out KdrantException>>>(
            Status.UNAUTHENTICATED to KdrantException.Unauthorized::class.java,
            Status.PERMISSION_DENIED to KdrantException.Unauthorized::class.java,
            Status.ALREADY_EXISTS to KdrantException.AlreadyExists::class.java,
            Status.DEADLINE_EXCEEDED to KdrantException.Timeout::class.java,
            Status.RESOURCE_EXHAUSTED to KdrantException.RateLimited::class.java,
            Status.UNAVAILABLE to KdrantException.ServiceUnavailable::class.java,
            Status.INTERNAL to KdrantException.ServerError::class.java,
            Status.DATA_LOSS to KdrantException.ServerError::class.java,
        )

        cases.forEach { (status, expected) ->
            val error = runCatching {
                GrpcErrors.mapping("docs") { throw status.asRuntimeException() }
            }.exceptionOrNull()

            assertTrue(expected.isInstance(error), "${status.code} mapped to ${error?.let { it::class.simpleName }}")
        }
    }

    @Test
    fun `both of gRPC's status exception types are translated, not only the runtime one`() = runTest {
        // The stubs throw StatusException; the older blocking API throws StatusRuntimeException. Both
        // reach this code, and catching only one would leak a raw gRPC type out of the seam.
        assertThrows<KdrantException.ServiceUnavailable> {
            GrpcErrors.mapping("docs") { throw StatusException(Status.UNAVAILABLE) }
        }
        assertThrows<KdrantException.ServiceUnavailable> {
            GrpcErrors.mapping("docs") { throw StatusRuntimeException(Status.UNAVAILABLE) }
        }
    }

    @Test
    fun `UNIMPLEMENTED says the server does not have the call, rather than blaming the request`() = runTest {
        val error = assertThrows<KdrantException.InvalidRequest> {
            GrpcErrors.mapping("docs") { throw Status.UNIMPLEMENTED.asRuntimeException() }
        }

        assertTrue(error.message!!.contains("does not implement"), error.message)
    }
}
