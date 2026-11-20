package com.nuvio.app.features.profiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import nuvio.composeapp.generated.resources.profile_pin_storage_unavailable

@Serializable
internal data class ProfilePinAttempts(val failures: Int = 0, val blockedUntil: Long = 0)

/** Call under the verification mutex. Reserve before checking so quitting cannot undo an attempt. */
internal class ProfilePinAttemptLimiter(
    private val load: (Int) -> String? = ProfilePinCacheStorage::loadAttempts,
    private val save: (Int, String) -> Unit = ProfilePinCacheStorage::saveAttempts,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    fun remaining(profile: Int): Int = ((state(profile).blockedUntil - now()).coerceIn(0, 900_000) + 999).div(1000).toInt()
    fun reserve(profile: Int) {
        val attempts = (state(profile).failures.coerceIn(0, 30) + 1).coerceAtMost(30)
        val delay = if (attempts < 5) 0L else (30_000L * (1L shl (attempts - 5).coerceAtMost(5))).coerceAtMost(900_000)
        save(profile, json.encodeToString(ProfilePinAttempts(attempts, now() + delay)))
    }
    fun success(profile: Int) { save(profile, json.encodeToString(ProfilePinAttempts())) }
    private fun state(profile: Int): ProfilePinAttempts = load(profile)?.let {
        runCatching { json.decodeFromString<ProfilePinAttempts>(it) }.getOrElse { ProfilePinAttempts(failures = 5) }
    } ?: ProfilePinAttempts()
}

internal fun verifyAndUpgradeProfilePin(
    profileIndex: Int,
    pin: String,
    cached: CachedProfilePinPayload,
    save: (CachedProfilePinPayload) -> Unit,
): Boolean {
    if (!ProfilePinCrypto.verify(profileIndex, cached.salt, pin, cached.digest)) return false
    if (ProfilePinCrypto.needsUpgrade(cached.digest)) {
        val salt = generateProfilePinSalt()
        runCatching { save(cached.copy(salt = salt, digest = hashProfilePin(profileIndex, salt, pin))) }
            .onFailure { co.touchlab.kermit.Logger.withTag("ProfilePin").w { "Could not update the saved PIN verifier; the previous verifier has been kept." } }
    }
    return true
}

internal class ProfilePinRequests(
    private val attempts: ProfilePinAttemptLimiter = ProfilePinAttemptLimiter(),
    private val unavailableMessage: suspend () -> String = {
        org.jetbrains.compose.resources.getString(nuvio.composeapp.generated.resources.Res.string.profile_pin_storage_unavailable)
    },
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun verify(profile: Int, check: suspend () -> PinVerifyResult): PinVerifyResult {
        mutex.lock()
        try {
            val remaining = attempts.remaining(profile)
            if (remaining > 0) return PinVerifyResult(retryAfterSeconds = remaining)
            attempts.reserve(profile)
            val result = check()
            if (result.unlocked) attempts.success(profile)
            return result.copy(retryAfterSeconds = maxOf(result.retryAfterSeconds, attempts.remaining(profile)))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return PinVerifyResult(message = unavailableMessage())
        } finally { mutex.unlock() }
    }
    suspend fun clear(profile: Int, check: suspend () -> PinVerifyResult): PinVerifyResult = verify(profile, check)
}
