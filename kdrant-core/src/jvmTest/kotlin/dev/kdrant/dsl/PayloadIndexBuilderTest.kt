package dev.kdrant.dsl

import dev.kdrant.kdrantJson
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.Tokenizer
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PayloadIndexBuilderTest {

    private fun build(configure: PayloadIndexBuilder.() -> Unit): PayloadIndexParams =
        PayloadIndexBuilder().apply(configure).build()

    private fun json(params: PayloadIndexParams): String = kdrantJson.encodeToString(params)

    @Test
    fun `a text index carries the tokenizer and the phrase-matching flag`() {
        val params = build { text { tokenizer = Tokenizer.WORD; phraseMatching = true; onDisk = true } }

        assertEquals(PayloadIndexParams.Text(Tokenizer.WORD, phraseMatching = true, onDisk = true), params)
        assertEquals(
            """{"type":"text","tokenizer":"word","phrase_matching":true,"on_disk":true}""",
            json(params),
        )
    }

    @Test
    fun `a keyword index carries is_tenant, which is what makes a multi-tenant layout`() {
        assertEquals(
            """{"type":"keyword","is_tenant":true,"on_disk":true}""",
            json(build { keyword { isTenant = true; onDisk = true } }),
        )
    }

    @Test
    fun `an integer index chooses lookups, ranges or both`() {
        assertEquals(
            """{"type":"integer","lookup":true,"range":false,"is_principal":true}""",
            json(build { integer { lookup = true; range = false; isPrincipal = true } }),
        )
    }

    @Test
    fun `the remaining five types serialize under their own discriminator`() {
        assertEquals("""{"type":"float","is_principal":true}""", json(build { float { isPrincipal = true } }))
        assertEquals("""{"type":"geo","on_disk":true}""", json(build { geo { onDisk = true } }))
        assertEquals("""{"type":"bool"}""", json(build { bool() }))
        assertEquals("""{"type":"datetime","on_disk":true}""", json(build { datetime { onDisk = true } }))
        assertEquals("""{"type":"uuid","is_tenant":true}""", json(build { uuid { isTenant = true } }))
    }

    @Test
    fun `an unset parameter is left off the request rather than sent as a default`() {
        // The server's defaults are the server's to choose. Sending `lowercase: true` because the
        // builder happened to initialize it would silently pin a default that Qdrant may change.
        assertEquals("""{"type":"text"}""", json(build { text() }))
    }

    @Test
    fun `a builder that names no type is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) { build { } }

        assertEquals(true, error.message?.contains("needs an index type"))
    }

    @Test
    fun `a builder that names two types is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { build { keyword(); text() } }
    }

    @Test
    fun `an integer index that answers neither lookups nor ranges is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { build { integer { lookup = false; range = false } } }
    }

    @Test
    fun `a token length range that indexes nothing is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { build { text { minTokenLen = 9; maxTokenLen = 3 } } }
        assertThrows(IllegalArgumentException::class.java) { build { text { minTokenLen = 0 } } }
    }
}
