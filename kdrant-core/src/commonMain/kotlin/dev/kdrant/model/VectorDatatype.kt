package dev.kdrant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Storage datatype for vector elements. Defaults to [FLOAT32] server-side when omitted. */
@Serializable
public enum class VectorDatatype {
    @SerialName("float32")
    FLOAT32,

    @SerialName("uint8")
    UINT8,

    @SerialName("float16")
    FLOAT16,

    /** TurboQuant 4-bit primary storage. Qdrant stores no original float vector. */
    @SerialName("turbo4")
    TURBO4,
}
