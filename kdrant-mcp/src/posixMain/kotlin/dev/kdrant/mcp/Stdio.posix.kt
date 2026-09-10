@file:OptIn(ExperimentalForeignApi::class)

package dev.kdrant.mcp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import platform.posix.read
import platform.posix.write

// Identical to the mingw copy by necessity rather than by accident: the bodies are the same call and the
// types underneath are not, which is the whole reason there are two of them.

internal actual fun readDescriptor(descriptor: Int, into: ByteArray, count: Int): Int =
    read(descriptor, into.refTo(0), count.convert()).convert()

internal actual fun writeDescriptor(descriptor: Int, from: ByteArray, offset: Int, count: Int): Int =
    write(descriptor, from.refTo(offset), count.convert()).convert()
