package io.github.tobsef.svgo.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert

/** POSIX `mkdir` takes a path and a mode. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun makeDirectory(path: String): Boolean =
    platform.posix.mkdir(path, "755".toUInt(8).convert()) == 0
