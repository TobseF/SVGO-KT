package io.github.tobsef.svgo.cli

import kotlinx.cinterop.ExperimentalForeignApi

/** Windows `mkdir` takes only a path. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun makeDirectory(path: String): Boolean = platform.posix.mkdir(path) == 0
