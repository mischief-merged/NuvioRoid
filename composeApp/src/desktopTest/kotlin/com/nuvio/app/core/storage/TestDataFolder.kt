package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path

/** Test stand-in for the old broad folder wipe. Production sign-out does not use it. */
internal fun wipeTestFolder(root: Path) {
    Files.list(root).use { entries ->
        entries.forEach { path ->
            val name = path.fileName?.toString().orEmpty()
            if (name == "downloads" || name == "moves") return@forEach
            path.toFile().deleteRecursively()
        }
    }
}
