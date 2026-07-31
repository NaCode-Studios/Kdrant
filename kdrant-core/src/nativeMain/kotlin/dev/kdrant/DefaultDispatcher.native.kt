package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin/Native declares an IO dispatcher but keeps it internal to the coroutines library, so this is
 * `Default` until it is published. A native application doing enough blocking to notice should pass its
 * own dispatcher rather than wait for that.
 */
internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
