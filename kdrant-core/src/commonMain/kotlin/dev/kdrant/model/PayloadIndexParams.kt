package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a text index splits a value into the tokens a `matchText` filter is matched against. */
@Serializable
public enum class Tokenizer {
    /** Every prefix of every word, so a partial word matches. Largest index. */
    @SerialName("prefix")
    PREFIX,

    /** Split on whitespace only, keeping punctuation attached to the token. */
    @SerialName("whitespace")
    WHITESPACE,

    /** Split on whitespace and punctuation. The usual choice for prose. */
    @SerialName("word")
    WORD,

    /** Word-splitting that also handles scripts without spaces, such as Chinese and Japanese. */
    @SerialName("multilingual")
    MULTILINGUAL,
}

/**
 * The parameters Qdrant accepts beside the index type, one shape per type.
 *
 * [PayloadSchemaType] names the kind of index; this names how it is built. Two of the choices decide
 * whether a query works at all rather than how fast it is: a `matchPhrase` filter matches nothing
 * unless the text index was created with [Text.phraseMatching], and an index built without
 * [Keyword.isTenant] leaves a multi-tenant collection's points scattered across the disk rather than
 * grouped per tenant. The rest are cost: `onDisk` is the difference between an index that has to fit
 * in RAM and one that does not.
 *
 * Build one with the DSL rather than by hand:
 *
 * ```kotlin
 * qdrant.createPayloadIndex("docs", "body") {
 *     text { tokenizer = Tokenizer.WORD; phraseMatching = true }
 * }
 * ```
 *
 * Qdrant takes two further text options this does not model, stopword sets and stemming, because both
 * are sub-objects with their own vocabulary rather than a flag. They are the reason this type is a
 * sealed hierarchy that can grow a field without breaking a caller.
 */
@Serializable
public sealed interface PayloadIndexParams {

    /** Whether Qdrant keeps this index on disk instead of in RAM. `null` leaves the server's default. */
    public val onDisk: Boolean?

    /**
     * A keyword index: exact matches on a string or a list of strings.
     *
     * @property isTenant tells Qdrant this field identifies a tenant, which makes it colocate one
     *   tenant's points on disk. The layout, not the filter, is what makes a multi-tenant collection
     *   fast; a filter on a non-tenant keyword field still reads the whole collection's pages.
     */
    @Serializable
    @SerialName("keyword")
    public data class Keyword(
        @SerialName("is_tenant") public val isTenant: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /**
     * An integer index.
     *
     * @property lookup answer equality lookups. Defaults to true on the server.
     * @property range answer range filters. Defaults to true on the server. Turning off the half you
     *   do not use is what makes the index smaller.
     * @property isPrincipal tells Qdrant to organize the collection's storage by this key, for a field
     *   that appears in the majority of filtered requests.
     */
    @Serializable
    @SerialName("integer")
    public data class Integer(
        @SerialName("lookup") public val lookup: Boolean? = null,
        @SerialName("range") public val range: Boolean? = null,
        @SerialName("is_principal") public val isPrincipal: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /** A float index. See [Integer.isPrincipal] for what `isPrincipal` decides. */
    @Serializable
    @SerialName("float")
    public data class Float(
        @SerialName("is_principal") public val isPrincipal: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /** A geo index, for `geoRadius`, `geoBoundingBox` and `geoPolygon` filters. */
    @Serializable
    @SerialName("geo")
    public data class Geo(
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /**
     * A full-text index, for `matchText`, `matchTextAny` and `matchPhrase`.
     *
     * @property tokenizer how a value is split into tokens; the server's default is [Tokenizer.WORD].
     * @property minTokenLen tokens shorter than this are dropped.
     * @property maxTokenLen tokens longer than this are dropped.
     * @property lowercase lowercase every token, making matching case-insensitive. Defaults to true.
     * @property phraseMatching store token positions so `matchPhrase` can require them adjacent and in
     *   order. Defaults to false, and a `matchPhrase` filter against an index without it is accepted
     *   by the server and matches nothing.
     */
    @Serializable
    @SerialName("text")
    public data class Text(
        @SerialName("tokenizer") public val tokenizer: Tokenizer? = null,
        @SerialName("min_token_len") public val minTokenLen: Int? = null,
        @SerialName("max_token_len") public val maxTokenLen: Int? = null,
        @SerialName("lowercase") public val lowercase: Boolean? = null,
        @SerialName("phrase_matching") public val phraseMatching: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /** A boolean index. */
    @Serializable
    @SerialName("bool")
    public data class Bool(
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /** A datetime index, for `datetimeRange` filters. See [Integer.isPrincipal]. */
    @Serializable
    @SerialName("datetime")
    public data class Datetime(
        @SerialName("is_principal") public val isPrincipal: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams

    /** A UUID index. See [Keyword.isTenant] for what `isTenant` decides. */
    @Serializable
    @SerialName("uuid")
    public data class Uuid(
        @SerialName("is_tenant") public val isTenant: Boolean? = null,
        @SerialName("on_disk") override val onDisk: Boolean? = null,
    ) : PayloadIndexParams
}
