package dev.kdrant.model

import kotlinx.serialization.json.JsonObject

/**
 * A point payload: an arbitrary JSON object with heterogeneous values (strings, numbers,
 * booleans, arrays, nested objects). Kept as a [JsonObject] rather than a fixed schema,
 * since Qdrant payloads are schemaless; typed access is left to the caller.
 */
public typealias Payload = JsonObject
