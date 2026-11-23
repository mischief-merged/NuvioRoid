package com.nuvio.app.features.plugins.runtime.js

import com.dokar.quickjs.QuickJs
import com.nuvio.app.features.plugins.runtime.closePluginEngine
import com.nuvio.app.features.plugins.runtime.configurePluginRuntime
import com.nuvio.app.features.plugins.runtime.createPluginEngine
import com.nuvio.app.features.plugins.runtime.withPluginDispatcher
import kotlin.concurrent.Volatile

internal class JsRuntime {
    suspend fun <T> use(block: suspend QuickJs.() -> T): T {
        return withPluginDispatcher { dispatcher ->
            val engine = createPluginEngine(dispatcher)
            try {
                engine.configurePluginRuntime()
                engine.block()
            } finally {
                closePluginEngine(engine)
            }
        }
    }

    companion object {
        @Volatile
        private var cachedPolyfillBytecode: ByteArray? = null

        @Volatile
        private var cachedCallBytecode: ByteArray? = null

        @Volatile
        private var cachedSettingsCallBytecode: ByteArray? = null

        fun polyfillBytecode(runtime: QuickJs): ByteArray =
            cachedPolyfillBytecode ?: runtime.compile(JsBindings.staticPolyfillCode, "polyfill.js", false).also {
                cachedPolyfillBytecode = it
            }

        fun callBytecode(runtime: QuickJs): ByteArray =
            cachedCallBytecode ?: runtime.compile(JsBindings.staticCallCode, "call.js", false).also {
                cachedCallBytecode = it
            }

        fun settingsCallBytecode(runtime: QuickJs): ByteArray =
            cachedSettingsCallBytecode
                ?: runtime.compile(JsBindings.staticSettingsCallCode, "settings-call.js", false).also {
                    cachedSettingsCallBytecode = it
                }
    }
}
