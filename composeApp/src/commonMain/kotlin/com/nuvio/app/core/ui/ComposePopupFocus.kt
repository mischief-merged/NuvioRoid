package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Tracks the [FocusManager] of the popup composition that currently owns the screen.
 *
 * Compose popups and modal sheets have their own focus owner; moving focus through the root
 * manager does nothing inside them. While a popup is visible its manager is on top of this stack
 * so the desktop key/gamepad bridge moves focus inside the popup (and Compose scrolls its lists).
 */
internal object ComposePopupFocus {
    private val lock = SynchronizedObject()
    private val managers = mutableListOf<FocusManager>()
    private val focusedManagers = mutableSetOf<FocusManager>()

    val active: FocusManager?
        get() = synchronized(lock) { managers.lastOrNull() }

    val hasActiveFocus: Boolean
        get() = synchronized(lock) {
            val current = managers.lastOrNull() ?: return@synchronized false
            focusedManagers.contains(current)
        }

    fun push(manager: FocusManager) {
        synchronized(lock) {
            managers.removeAll { it === manager }
            managers.add(manager)
        }
    }

    fun pop(manager: FocusManager) {
        synchronized(lock) {
            managers.removeAll { it === manager }
            focusedManagers.remove(manager)
        }
    }

    fun setFocused(manager: FocusManager, hasFocus: Boolean) {
        synchronized(lock) {
            if (hasFocus) {
                focusedManagers.add(manager)
            } else {
                focusedManagers.remove(manager)
            }
        }
    }
}
