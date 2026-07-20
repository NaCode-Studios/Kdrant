package dev.kdrant.internal

import dev.kdrant.kdrantJson
import kotlinx.serialization.json.Json

/**
 * Internal handle on the canonical [dev.kdrant.kdrantJson] configuration, used by the wire engine
 * and by serialization tests. Kept as an opt-in internal symbol so wire-level use stays explicit;
 * user code should use the public [dev.kdrant.kdrantJson].
 */
@InternalKdrantApi
public val KdrantJson: Json = kdrantJson
