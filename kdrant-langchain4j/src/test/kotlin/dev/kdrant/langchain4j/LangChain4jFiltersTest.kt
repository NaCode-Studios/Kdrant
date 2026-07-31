package dev.kdrant.langchain4j

import dev.kdrant.kdrantJson
import dev.langchain4j.store.embedding.filter.Filter
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import dev.kdrant.model.Filter as KdrantFilter

class LangChain4jFiltersTest {

    /** Translate, then render the wire JSON Qdrant would receive. */
    private fun translate(filter: Filter): String =
        kdrantJson.encodeToString(KdrantFilter.serializer(), filter.toKdrantFilter())

    private fun assertJson(expected: String, actual: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(actual),
            "JSON mismatch.\n  expected: $expected\n  actual:   $actual",
        )
    }

    @Test
    fun `isEqualTo becomes a match condition`() {
        assertJson(
            """{"must":[{"key":"lang","match":{"value":"en"}}]}""",
            translate(metadataKey("lang").isEqualTo("en")),
        )
    }

    @Test
    fun `isNotEqualTo becomes a negated match condition`() {
        assertJson(
            """{"must":[{"must_not":[{"key":"lang","match":{"value":"en"}}]}]}""",
            translate(metadataKey("lang").isNotEqualTo("en")),
        )
    }

    @Test
    fun `a chain of ands flattens into one must clause`() {
        assertJson(
            """
            {"must":[
              {"key":"lang","match":{"value":"en"}},
              {"key":"year","range":{"gte":2020.0}},
              {"key":"draft","match":{"value":"no"}}
            ]}
            """.trimIndent(),
            translate(
                metadataKey("lang").isEqualTo("en")
                    .and(metadataKey("year").isGreaterThanOrEqualTo(2020))
                    .and(metadataKey("draft").isEqualTo("no")),
            ),
        )
    }

    @Test
    fun `a chain of ors flattens into one should clause`() {
        assertJson(
            """
            {"should":[
              {"key":"lang","match":{"value":"en"}},
              {"key":"lang","match":{"value":"it"}},
              {"key":"lang","match":{"value":"fr"}}
            ]}
            """.trimIndent(),
            translate(
                metadataKey("lang").isEqualTo("en")
                    .or(metadataKey("lang").isEqualTo("it"))
                    .or(metadataKey("lang").isEqualTo("fr")),
            ),
        )
    }

    @Test
    fun `an or nested inside an and keeps its own clause`() {
        assertJson(
            """
            {"must":[
              {"should":[
                {"key":"lang","match":{"value":"en"}},
                {"key":"lang","match":{"value":"it"}}
              ]},
              {"key":"year","range":{"gte":2020.0}}
            ]}
            """.trimIndent(),
            translate(
                metadataKey("lang").isEqualTo("en").or(metadataKey("lang").isEqualTo("it"))
                    .and(metadataKey("year").isGreaterThanOrEqualTo(2020)),
            ),
        )
    }

    @Test
    fun `not becomes a must_not clause`() {
        assertJson(
            """{"must_not":[{"key":"lang","match":{"value":"en"}}]}""",
            translate(Filter.not(metadataKey("lang").isEqualTo("en"))),
        )
    }

    @Test
    fun `every numeric comparison maps to its range bound`() {
        assertJson("""{"must":[{"key":"n","range":{"gt":1.0}}]}""", translate(metadataKey("n").isGreaterThan(1)))
        assertJson(
            """{"must":[{"key":"n","range":{"gte":1.0}}]}""",
            translate(metadataKey("n").isGreaterThanOrEqualTo(1)),
        )
        assertJson("""{"must":[{"key":"n","range":{"lt":1.0}}]}""", translate(metadataKey("n").isLessThan(1)))
        assertJson(
            """{"must":[{"key":"n","range":{"lte":1.0}}]}""",
            translate(metadataKey("n").isLessThanOrEqualTo(1)),
        )
    }

    @Test
    fun `a string comparison bound is sent as a datetime range`() {
        assertJson(
            """{"must":[{"key":"created","range":{"gte":"2026-01-01T00:00:00Z"}}]}""",
            translate(metadataKey("created").isGreaterThanOrEqualTo("2026-01-01T00:00:00Z")),
        )
    }

    @Test
    fun `isIn and isNotIn become match any and match except`() {
        assertJson(
            """{"must":[{"key":"lang","match":{"any":["en","it"]}}]}""",
            translate(metadataKey("lang").isIn("en", "it")),
        )
        assertJson(
            """{"must":[{"key":"lang","match":{"except":["en","it"]}}]}""",
            translate(metadataKey("lang").isNotIn("en", "it")),
        )
    }

    @Test
    fun `containsString becomes a full-text match`() {
        assertJson(
            """{"must":[{"key":"title","match":{"text":"vector"}}]}""",
            translate(metadataKey("title").containsString("vector")),
        )
    }

    @Test
    fun `a UUID comparison value is sent as its string form`() {
        val id = UUID.fromString("0b7f2e4a-2f4a-4f6e-9c1b-8b0f0a5e7d31")

        assertJson(
            """{"must":[{"key":"doc","match":{"value":"$id"}}]}""",
            translate(metadataKey("doc").isEqualTo(id)),
        )
    }

    @Test
    fun `a filter outside the LangChain4j model is rejected rather than silently dropped`() {
        val custom = Filter { true }

        val error = assertThrows(IllegalArgumentException::class.java) { custom.toKdrantFilter() }
        assertEquals(true, error.message!!.contains("cannot translate"))
    }

    @Test
    fun `a range comparison against a non-comparable value is rejected`() {
        val filter = dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan("flag", true)

        val error = assertThrows(IllegalArgumentException::class.java) { filter.toKdrantFilter() }
        assertEquals(true, error.message!!.contains("RFC 3339"))
    }
}
