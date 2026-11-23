package com.nuvio.app.core.ui

/** A missing or failed image still has to show the title on the card itself. */
internal fun posterShowsTitleOnFace(imageUrl: String?, imageFailed: Boolean): Boolean =
    imageUrl.isNullOrBlank() || imageFailed

internal fun nextPosterAttempt(attempt: Int): Int = attempt + 1
