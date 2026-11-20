package com.nuvio.app.features.profiles

internal expect object ProfilePinCacheStorage {
    fun loadAttempts(profileIndex: Int): String?
    fun saveAttempts(profileIndex: Int, payload: String)
    fun loadPayload(profileIndex: Int): String?
    fun savePayload(profileIndex: Int, payload: String)
    fun removePayload(profileIndex: Int)
}