package com.nuvio.app.features.player.desktop

internal fun NativePlayerController.setNativeHandleForTest(value: Long) {
    javaClass.getDeclaredField("handle").also { field ->
        field.isAccessible = true
        field.setLong(this, value)
    }
}
