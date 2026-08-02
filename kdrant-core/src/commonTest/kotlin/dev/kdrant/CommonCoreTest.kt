package dev.kdrant

import dev.kdrant.dsl.filter
import dev.kdrant.dsl.payloadOf
import dev.kdrant.model.Condition
import dev.kdrant.model.Distance
import dev.kdrant.model.FieldMatcher
import dev.kdrant.model.Filter
import dev.kdrant.model.PointId
import dev.kdrant.model.VectorData
import dev.kdrant.model.VectorParams
import dev.kdrant.model.VectorsConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The core's behaviour, run on every target it now builds for.
 *
 * The bulk of the test suite stays on the JVM: it is written against JUnit 5 and kotest-property, both
 * of which are JVM-only, and rewriting eighty tests to move them would be a large mechanical change
 * with nothing to show for it — the same assertions, running in more places, testing the same
 * platform-independent code.
 *
 * What is here instead is what would actually differ per platform if anything did: number handling on
 * the way to and from JSON, which is where a JS `Number` and a JVM `Long` disagree; the serializers
 * that are hand-written rather than generated; and the two places the core touches coroutines. A
 * failure here is a platform difference, not a logic error, which is what makes the file worth its
 * duplication.
 */
class CommonCoreTest {

    @Test
    fun `a point id above Long MAX survives the JSON round trip on every platform`() {
        // The reason this is not a JVM-only test: JS numbers are doubles, so an id near 2^64 is exactly
        // where a platform would quietly lose precision if the serializer went through a number type
        // rather than through the literal.
        val id: PointId = PointId.num(ULong.MAX_VALUE)

        val json = kdrantJson.encodeToString(PointId.serializer(), id)

        assertEquals("18446744073709551615", json)
        assertEquals(id, kdrantJson.decodeFromString(PointId.serializer(), json))
    }

    @Test
    fun `a uuid id stays a quoted string so it is never read back as a number`() {
        val id: PointId = PointId.uuid("550e8400-e29b-41d4-a716-446655440000")

        val json = kdrantJson.encodeToString(PointId.serializer(), id)

        assertTrue(json.startsWith("\""), json)
        assertEquals(id, kdrantJson.decodeFromString(PointId.serializer(), json))
    }

    @Test
    fun `a negative numeric id is refused because Qdrant ids are unsigned`() {
        assertFailsWith<IllegalArgumentException> { PointId.num(-1L) }
    }

    @Test
    fun `the filter DSL builds the clauses it was given`() {
        val built = filter {
            must { "lang" eq "it"; "year" gte 2020 }
            mustNot { isEmpty("title") }
        }

        assertEquals(2, built.must?.size)
        assertEquals(Condition.Field("lang", FieldMatcher.Match(JsonPrimitive("it"))), built.must?.first())
        assertEquals(listOf(Condition.IsEmpty("title")), built.mustNot)
    }

    @Test
    fun `a filter serializes to the shape Qdrant reads`() {
        val json = kdrantJson.encodeToString(Filter.serializer(), filter { must { "lang" eq "it" } })

        assertEquals("""{"must":[{"key":"lang","match":{"value":"it"}}]}""", json)
    }

    @Test
    fun `a named vectors config serializes as a map and a single one as a bare object`() {
        val single = kdrantJson.encodeToString(
            VectorsConfig.serializer(),
            VectorsConfig.Single(VectorParams(size = 4, distance = Distance.COSINE)),
        )
        val named = kdrantJson.encodeToString(
            VectorsConfig.serializer(),
            VectorsConfig.Named(mapOf("text" to VectorParams(size = 4, distance = Distance.DOT))),
        )

        assertEquals("""{"size":4,"distance":"Cosine"}""", single)
        assertEquals("""{"text":{"size":4,"distance":"Dot"}}""", named)
    }

    @Test
    fun `a dense vector and its FloatArray fast path serialize identically`() {
        val boxed = kdrantJson.encodeToString(VectorData.serializer(), VectorData.Dense(listOf(0.5f, 0.25f)))
        val array = kdrantJson.encodeToString(VectorData.serializer(), VectorData.DenseArray(floatArrayOf(0.5f, 0.25f)))

        assertEquals("[0.5,0.25]", boxed)
        assertEquals(boxed, array)
    }

    @Test
    fun `a payload keeps an integer an integer rather than rounding it through a double`() {
        val payload = payloadOf("year" to 2024, "big" to 9_007_199_254_740_993L)

        val json = kdrantJson.encodeToString(JsonObject.serializer(), payload)

        // 2^53 + 1 is the first integer a double cannot represent; it is here because JS would be the
        // platform to lose it.
        assertEquals("""{"year":2024,"big":9007199254740993}""", json)
    }

    @Test
    fun `a credential sent across a network without TLS is refused`() {
        val key = assertFailsWith<IllegalArgumentException> {
            kdrantConfig("qdrant.example.com", 6333) { apiKey = "secret" }
        }
        val token = assertFailsWith<IllegalArgumentException> {
            kdrantConfig("qdrant.example.com", 6333) { bearerToken = "a.jwt.value" }
        }

        assertTrue(key.message!!.contains("useTls"), key.message)
        assertTrue(token.message!!.contains("useTls"), token.message)
    }

    @Test
    fun `a credential to this machine needs no TLS, because nothing leaves it`() {
        assertEquals("secret", kdrantConfig("localhost", 6333) { apiKey = "secret" }.apiKey)
        assertEquals("secret", kdrantConfig("127.0.0.1", 6333) { apiKey = "secret" }.apiKey)
        assertEquals("a.jwt.value", kdrantConfig("::1", 6333) { bearerToken = "a.jwt.value" }.bearerToken)
    }

    @Test
    fun `an api key and a bearer token cannot both be set`() {
        assertFailsWith<IllegalArgumentException> {
            kdrantConfig("localhost", 6333) { apiKey = "secret"; bearerToken = "a.jwt.value" }
        }
    }

    @Test
    fun `a port outside the range is refused`() {
        assertFailsWith<IllegalArgumentException> { kdrantConfig("localhost", 0) }
        assertFailsWith<IllegalArgumentException> { kdrantConfig("localhost", 70_000) }
    }

    @Test
    fun `the config never prints either credential`() {
        val key = kdrantConfig("localhost", 6333) { apiKey = "secret"; useTls = true }.toString()
        val token = kdrantConfig("localhost", 6333) { bearerToken = "a.jwt.value"; useTls = true }.toString()

        assertTrue(key.contains("apiKey=***"), key)
        assertTrue(!key.contains("secret"), key)
        assertTrue(token.contains("bearerToken=***"), token)
        assertTrue(!token.contains("a.jwt.value"), token)
    }

    @Test
    fun `catching captures a failure and re-throws a cancellation`() = runTest {
        assertTrue(catching { error("boom") }.isFailure)

        assertFailsWith<CancellationException> {
            catching { throw CancellationException("cancelled") }
        }
    }
}
