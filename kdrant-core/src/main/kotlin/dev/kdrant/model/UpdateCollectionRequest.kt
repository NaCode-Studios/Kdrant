package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Request body for `PATCH /collections/{name}` — tune an existing collection's config. */
@Serializable
public data class UpdateCollectionRequest(
    @SerialName("optimizers_config")
    public val optimizersConfig: OptimizersConfig? = null,

    @SerialName("hnsw_config")
    public val hnswConfig: HnswConfig? = null,

    @SerialName("quantization_config")
    public val quantizationConfig: QuantizationConfig? = null,
)
