package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopAccountFiles

internal actual object AccountFiles {
    actual fun onLostSession() {
        DesktopAccountFiles.onLostSession(DesktopAccountFiles.root())
    }

    actual fun beforeSignOut(): Result<Unit> =
        DesktopAccountFiles.beforeSignOut(DesktopAccountFiles.root(), DesktopAccountFiles.clock())
}
