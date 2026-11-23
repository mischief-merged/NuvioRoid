package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
internal val pluginDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_PLUGINS)

internal fun QuickJs.configurePluginRuntime() = Unit

internal suspend fun <T> withPluginDispatcher(block: suspend (CoroutineDispatcher) -> T): T =
    withContext(pluginDispatcher) { block(pluginDispatcher) }

internal fun createPluginEngine(dispatcher: CoroutineDispatcher): QuickJs = QuickJs.create(dispatcher)

internal fun closePluginEngine(engine: QuickJs) = engine.close()
