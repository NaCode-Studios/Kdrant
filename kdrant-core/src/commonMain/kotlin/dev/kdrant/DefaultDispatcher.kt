package dev.kdrant

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher a client runs on when the caller does not choose one.
 *
 * This is the only platform-dependent declaration in the core, and the reason it has to be one is that
 * only the JVM publishes an IO dispatcher. That is the right default where it exists: a Qdrant client
 * blocks on a socket rather than on a CPU, so its work belongs on the elastic pool and not on the one
 * sized to the number of cores.
 *
 * On Kotlin/Native it is `Dispatchers.Default`, because the IO dispatcher that exists there is still
 * internal to the coroutines library.
 */
internal expect val ioDispatcher: CoroutineDispatcher
