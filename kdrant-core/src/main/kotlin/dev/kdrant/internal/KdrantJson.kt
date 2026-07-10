package dev.kdrant.internal

import kotlinx.serialization.json.Json

/**
 * The single [Json] configuration used to (de)serialize Qdrant wire payloads.
 *
 * - `encodeDefaults = false` + `explicitNulls = false`: never emit `null`/default fields, because
 *   Qdrant distinguishes an absent field from an explicit `null` in several places
 *   (e.g. filter range bounds).
 * - `ignoreUnknownKeys = true`: forward-compatible with new server fields.
 */
@InternalKdrantApi
public val KdrantJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}
