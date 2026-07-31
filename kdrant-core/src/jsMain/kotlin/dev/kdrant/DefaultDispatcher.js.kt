package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** JS has no IO dispatcher and nothing to move off the event loop, so Default is the whole story. */
internal actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
