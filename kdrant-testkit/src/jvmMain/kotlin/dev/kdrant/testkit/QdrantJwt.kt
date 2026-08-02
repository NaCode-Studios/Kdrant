package dev.kdrant.testkit

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints the Qdrant JWTs a test needs, and nothing more.
 *
 * Qdrant signs its access tokens with HMAC-SHA256 over the node's own API key, so a test that wants a
 * read-only or collection-scoped client can produce one without a token service. That is the whole
 * scope: there is no key rotation here, no expiry policy, no storage. Minting tokens for a running
 * system is Qdrant's job and the deployment's, and a helper that looked like a key-management story
 * would be read as one.
 *
 * The claims follow Qdrant's granular access control:
 *
 * - `{"access": "r"}` — read anything, write nothing.
 * - `{"access": "m"}` — manage: everything the master key can do.
 * - `{"access": [{"collection": "docs", "access": "r"}]}` — read one collection and no other.
 * - a `payload` on a collection claim scopes the token to the points matching it, which is how one
 *   tenant's search is kept from reading another tenant's points.
 *
 * See [Qdrant's security guide](https://qdrant.tech/documentation/guides/security/).
 */
public object QdrantJwt {

    /** A token that may read every collection and write to none. */
    public fun readOnly(apiKey: String): String = sign(apiKey, """{"access":"r"}""")

    /** A token scoped to one collection, read-only unless [write]. */
    public fun forCollection(apiKey: String, collection: String, write: Boolean = false): String =
        sign(
            apiKey,
            """{"access":[{"collection":"${collection.escaped()}","access":"${if (write) "rw" else "r"}"}]}""",
        )

    /**
     * A read-only token that can only see the points whose payload has [field] equal to [value] — the
     * per-tenant case, where the filter is the isolation rather than a convention the application is
     * trusted to follow.
     */
    public fun forTenant(apiKey: String, collection: String, field: String, value: String): String =
        sign(
            apiKey,
            """{"access":[{"collection":"${collection.escaped()}","access":"r",""" +
                """"payload":{"${field.escaped()}":"${value.escaped()}"}}]}""",
        )

    /** Signs [claimsJson] as a compact JWS with HMAC-SHA256 over [apiKey], Qdrant's signing scheme. */
    public fun sign(apiKey: String, claimsJson: String): String {
        val signingInput = "${encode(HEADER.toByteArray())}.${encode(claimsJson.toByteArray())}"
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA256"))
        }
        return "$signingInput.${encode(mac.doFinal(signingInput.toByteArray()))}"
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Enough escaping for a collection or payload value in a test. Not a JSON encoder. */
    private fun String.escaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""
}
