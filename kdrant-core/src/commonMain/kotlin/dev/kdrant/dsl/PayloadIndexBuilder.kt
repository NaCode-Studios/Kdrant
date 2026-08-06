package dev.kdrant.dsl

import dev.kdrant.KdrantDsl
import dev.kdrant.model.PayloadIndexParams
import dev.kdrant.model.Tokenizer

/**
 * DSL for `createPayloadIndex`, choosing the index type and the parameters that type accepts.
 *
 * Exactly one type per call, because a field carries one index:
 *
 * ```kotlin
 * qdrant.createPayloadIndex("docs", "body") {
 *     text { tokenizer = Tokenizer.WORD; phraseMatching = true }
 * }
 * qdrant.createPayloadIndex("docs", "tenant") { keyword { isTenant = true; onDisk = true } }
 * ```
 *
 * A type function with an empty body asks for that index with the server's defaults, which is the
 * three-argument `createPayloadIndex(name, field, schema)` overload written differently.
 */
@KdrantDsl
public class PayloadIndexBuilder {

    private var params: PayloadIndexParams? = null

    /** A keyword index: exact matches on a string or a list of strings. */
    public fun keyword(configure: KeywordIndexBuilder.() -> Unit = {}) {
        set(KeywordIndexBuilder().apply(configure).build())
    }

    /** An integer index, answering equality lookups, ranges or both. */
    public fun integer(configure: IntegerIndexBuilder.() -> Unit = {}) {
        set(IntegerIndexBuilder().apply(configure).build())
    }

    /** A float index. */
    public fun float(configure: FloatIndexBuilder.() -> Unit = {}) {
        set(FloatIndexBuilder().apply(configure).build())
    }

    /** A geo index, for the geo matchers. */
    public fun geo(configure: GeoIndexBuilder.() -> Unit = {}) {
        set(GeoIndexBuilder().apply(configure).build())
    }

    /** A full-text index, for `matchText`, `matchTextAny` and `matchPhrase`. */
    public fun text(configure: TextIndexBuilder.() -> Unit = {}) {
        set(TextIndexBuilder().apply(configure).build())
    }

    /** A boolean index. */
    public fun bool(configure: BoolIndexBuilder.() -> Unit = {}) {
        set(BoolIndexBuilder().apply(configure).build())
    }

    /** A datetime index, for `datetimeRange` filters. */
    public fun datetime(configure: DatetimeIndexBuilder.() -> Unit = {}) {
        set(DatetimeIndexBuilder().apply(configure).build())
    }

    /** A UUID index. */
    public fun uuid(configure: UuidIndexBuilder.() -> Unit = {}) {
        set(UuidIndexBuilder().apply(configure).build())
    }

    private fun set(value: PayloadIndexParams) {
        require(params == null) {
            "A field carries one index: this builder already asked for ${params!!::class.simpleName}"
        }
        params = value
    }

    internal fun build(): PayloadIndexParams = requireNotNull(params) {
        "createPayloadIndex needs an index type: call keyword { }, text { }, integer { }, and so on"
    }
}

/** Parameters of a keyword index. */
@KdrantDsl
public class KeywordIndexBuilder {
    /** Colocate one tenant's points on disk. See [PayloadIndexParams.Keyword.isTenant]. */
    public var isTenant: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Keyword = PayloadIndexParams.Keyword(isTenant, onDisk)
}

/** Parameters of an integer index. */
@KdrantDsl
public class IntegerIndexBuilder {
    /** Answer equality lookups. Defaults to true on the server. */
    public var lookup: Boolean? = null

    /** Answer range filters. Defaults to true on the server. */
    public var range: Boolean? = null

    /** Organize the collection's storage by this key. See [PayloadIndexParams.Integer.isPrincipal]. */
    public var isPrincipal: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Integer {
        require(lookup != false || range != false) {
            "An integer index that answers neither lookups nor ranges answers nothing: leave one of " +
                "lookup and range unset or true."
        }
        return PayloadIndexParams.Integer(lookup, range, isPrincipal, onDisk)
    }
}

/** Parameters of a float index. */
@KdrantDsl
public class FloatIndexBuilder {
    /** Organize the collection's storage by this key. See [PayloadIndexParams.Integer.isPrincipal]. */
    public var isPrincipal: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Float = PayloadIndexParams.Float(isPrincipal, onDisk)
}

/** Parameters of a geo index. */
@KdrantDsl
public class GeoIndexBuilder {
    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Geo = PayloadIndexParams.Geo(onDisk)
}

/** Parameters of a full-text index. */
@KdrantDsl
public class TextIndexBuilder {
    /** How a value is split into tokens. The server's default is [Tokenizer.WORD]. */
    public var tokenizer: Tokenizer? = null

    /** Drop tokens shorter than this. */
    public var minTokenLen: Int? = null

    /** Drop tokens longer than this. */
    public var maxTokenLen: Int? = null

    /** Lowercase every token, making matching case-insensitive. Defaults to true on the server. */
    public var lowercase: Boolean? = null

    /** Store token positions so `matchPhrase` works. See [PayloadIndexParams.Text.phraseMatching]. */
    public var phraseMatching: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Text {
        minTokenLen?.let { require(it > 0) { "minTokenLen must be > 0, was $it" } }
        maxTokenLen?.let { require(it > 0) { "maxTokenLen must be > 0, was $it" } }
        val min = minTokenLen
        val max = maxTokenLen
        if (min != null && max != null) {
            require(min <= max) { "minTokenLen ($min) must be <= maxTokenLen ($max), or nothing is indexed" }
        }
        return PayloadIndexParams.Text(tokenizer, min, max, lowercase, phraseMatching, onDisk)
    }
}

/** Parameters of a boolean index. */
@KdrantDsl
public class BoolIndexBuilder {
    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Bool = PayloadIndexParams.Bool(onDisk)
}

/** Parameters of a datetime index. */
@KdrantDsl
public class DatetimeIndexBuilder {
    /** Organize the collection's storage by this key. See [PayloadIndexParams.Integer.isPrincipal]. */
    public var isPrincipal: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Datetime = PayloadIndexParams.Datetime(isPrincipal, onDisk)
}

/** Parameters of a UUID index. */
@KdrantDsl
public class UuidIndexBuilder {
    /** Colocate one tenant's points on disk. See [PayloadIndexParams.Keyword.isTenant]. */
    public var isTenant: Boolean? = null

    /** Keep the index on disk instead of in RAM. */
    public var onDisk: Boolean? = null

    internal fun build(): PayloadIndexParams.Uuid = PayloadIndexParams.Uuid(isTenant, onDisk)
}
