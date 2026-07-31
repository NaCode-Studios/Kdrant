package dev.kdrant

import dev.kdrant.dsl.SearchBuilder
import dev.kdrant.model.PointId
import dev.kdrant.model.Record
import dev.kdrant.model.ScoredPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The default [Json] Kdrant uses to decode point payloads.
 *
 * - `ignoreUnknownKeys = true`: decoding a payload into your own type silently ignores fields you
 *   did not model.
 * - `encodeDefaults = false` + `explicitNulls = false`: never emit `null`/default fields, matching how
 *   Qdrant distinguishes an absent field from an explicit `null`.
 *
 * Used as the default by [payloadAs] and [searchAs]; pass your own [Json] to either to override it.
 */
public val kdrantJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** A typed search hit: a point [id], its similarity [score], and the decoded [payload]. */
public data class Hit<T>(
    public val id: PointId,
    public val score: Float,
    public val payload: T?,
)

/**
 * Decode this hit's payload into [T] with [json], or return `null` if the hit carries no payload.
 *
 * ```kotlin
 * @Serializable data class Article(val title: String, val lang: String)
 * val article: Article? = hit.payloadAs<Article>()
 * ```
 */
public inline fun <reified T> ScoredPoint.payloadAs(json: Json = kdrantJson): T? =
    payload?.let { json.decodeFromJsonElement<T>(it) }

/** Decode this record's payload into [T] with [json], or return `null` if it carries no payload. */
public inline fun <reified T> Record.payloadAs(json: Json = kdrantJson): T? =
    payload?.let { json.decodeFromJsonElement<T>(it) }

/**
 * Like [QdrantClient.search], but decodes each hit's payload into [T] — the typical RAG read path.
 *
 * ```kotlin
 * val hits: List<Hit<Article>> = qdrant.searchAs<Article>("articles") {
 *     query(queryVector)
 *     limit = 5
 * }
 * ```
 *
 * @param json the [Json] used to decode payloads (defaults to [kdrantJson]).
 */
public suspend inline fun <reified T> QdrantClient.searchAs(
    name: String,
    json: Json = kdrantJson,
    noinline configure: SearchBuilder.() -> Unit,
): List<Hit<T>> =
    search(name, configure).map { Hit(it.id, it.score, it.payloadAs<T>(json)) }
