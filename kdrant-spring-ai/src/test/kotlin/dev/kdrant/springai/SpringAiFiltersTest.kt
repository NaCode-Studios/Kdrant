package dev.kdrant.springai

import dev.kdrant.kdrantJson
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser
import dev.kdrant.model.Filter as KdrantFilter
import org.springframework.ai.vectorstore.filter.Filter as SpringAiFilter

class SpringAiFiltersTest {

    private val parser = FilterExpressionTextParser()

    /** Parse Spring AI's own filter syntax, translate, and render the wire JSON Qdrant would receive. */
    private fun translate(expression: String): String =
        kdrantJson.encodeToString(KdrantFilter.serializer(), parser.parse(expression).toKdrantFilter())

    private fun assertJson(expected: String, actual: String) {
        assertEquals(
            Json.parseToJsonElement(expected),
            Json.parseToJsonElement(actual),
            "JSON mismatch.\n  expected: $expected\n  actual:   $actual",
        )
    }

    @Test
    fun `equality becomes a match condition`() {
        assertJson("""{"must":[{"key":"lang","match":{"value":"en"}}]}""", translate("lang == 'en'"))
    }

    @Test
    fun `inequality becomes a negated match condition`() {
        assertJson(
            """{"must":[{"must_not":[{"key":"lang","match":{"value":"en"}}]}]}""",
            translate("lang != 'en'"),
        )
    }

    @Test
    fun `a chain of ANDs flattens into one must clause`() {
        assertJson(
            """
            {"must":[
              {"key":"lang","match":{"value":"en"}},
              {"key":"year","range":{"gte":2020.0}},
              {"key":"draft","match":{"value":false}}
            ]}
            """.trimIndent(),
            translate("lang == 'en' && year >= 2020 && draft == false"),
        )
    }

    @Test
    fun `a chain of ORs flattens into one should clause`() {
        assertJson(
            """
            {"should":[
              {"key":"lang","match":{"value":"en"}},
              {"key":"lang","match":{"value":"it"}},
              {"key":"lang","match":{"value":"fr"}}
            ]}
            """.trimIndent(),
            translate("lang == 'en' || lang == 'it' || lang == 'fr'"),
        )
    }

    @Test
    fun `a parenthesised group is not flattened into its parent clause`() {
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
            translate("(lang == 'en' || lang == 'it') && year >= 2020"),
        )
    }

    @Test
    fun `NOT becomes a must_not clause`() {
        assertJson(
            """{"must_not":[{"key":"lang","match":{"value":"en"}}]}""",
            translate("NOT(lang == 'en')"),
        )
    }

    @Test
    fun `every numeric comparison maps to its range bound`() {
        assertJson("""{"must":[{"key":"n","range":{"gt":1.0}}]}""", translate("n > 1"))
        assertJson("""{"must":[{"key":"n","range":{"gte":1.0}}]}""", translate("n >= 1"))
        assertJson("""{"must":[{"key":"n","range":{"lt":1.0}}]}""", translate("n < 1"))
        assertJson("""{"must":[{"key":"n","range":{"lte":1.0}}]}""", translate("n <= 1"))
    }

    @Test
    fun `a string comparison bound is sent as a datetime range`() {
        assertJson(
            """{"must":[{"key":"created","range":{"gte":"2026-01-01T00:00:00Z"}}]}""",
            translate("created >= '2026-01-01T00:00:00Z'"),
        )
    }

    @Test
    fun `IN and NIN become match any and match except`() {
        assertJson(
            """{"must":[{"key":"lang","match":{"any":["en","it"]}}]}""",
            translate("lang in ['en', 'it']"),
        )
        assertJson(
            """{"must":[{"key":"lang","match":{"except":["en","it"]}}]}""",
            translate("lang nin ['en', 'it']"),
        )
    }

    @Test
    fun `IS NULL becomes is_empty, which also covers a missing key`() {
        assertJson("""{"must":[{"is_empty":{"key":"lang"}}]}""", translate("lang IS NULL"))
        assertJson(
            """{"must":[{"must_not":[{"is_empty":{"key":"lang"}}]}]}""",
            translate("lang IS NOT NULL"),
        )
    }

    @Test
    fun `a value that is not a scalar is rejected rather than silently dropped`() {
        val expression = SpringAiFilter.Expression(
            SpringAiFilter.ExpressionType.EQ,
            SpringAiFilter.Key("lang"),
            SpringAiFilter.Value(Any()),
        )

        val error = assertThrows(IllegalArgumentException::class.java) { expression.toKdrantFilter() }
        assertEquals(true, error.message!!.contains("String, Number or Boolean"))
    }

    @Test
    fun `a range comparison against a non-comparable value is rejected`() {
        val expression = SpringAiFilter.Expression(
            SpringAiFilter.ExpressionType.GT,
            SpringAiFilter.Key("n"),
            SpringAiFilter.Value(true),
        )

        val error = assertThrows(IllegalArgumentException::class.java) { expression.toKdrantFilter() }
        assertEquals(true, error.message!!.contains("RFC 3339"))
    }
}
