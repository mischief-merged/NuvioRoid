package com.nuvio.app.features.home

internal enum class EmptyScreenAction {
    AddSource,
    ConnectServer,
    OpenDownloads,
    Retry,
}

internal fun setupEmptyActions(offline: Boolean): List<EmptyScreenAction> =
    if (offline) {
        listOf(
            EmptyScreenAction.AddSource,
            EmptyScreenAction.ConnectServer,
            EmptyScreenAction.OpenDownloads,
        )
    } else {
        listOf(EmptyScreenAction.AddSource, EmptyScreenAction.ConnectServer)
    }

internal fun failedEmptyActions(offline: Boolean): List<EmptyScreenAction> =
    if (offline) {
        listOf(EmptyScreenAction.Retry, EmptyScreenAction.OpenDownloads)
    } else {
        listOf(EmptyScreenAction.Retry)
    }

internal fun visibleEmptyActions(
    actions: List<EmptyScreenAction>,
    downloadsEnabled: Boolean,
): List<EmptyScreenAction> =
    if (downloadsEnabled) actions else actions.filter { it != EmptyScreenAction.OpenDownloads }

internal fun performEmptyAction(
    action: EmptyScreenAction,
    onAddSource: () -> Unit,
    onConnectServer: () -> Unit,
    onOpenDownloads: () -> Unit,
    onRetry: () -> Unit,
) {
    when (action) {
        EmptyScreenAction.AddSource -> onAddSource()
        EmptyScreenAction.ConnectServer -> onConnectServer()
        EmptyScreenAction.OpenDownloads -> onOpenDownloads()
        EmptyScreenAction.Retry -> onRetry()
    }
}
