package za.co.arsmarthome.mediabridge

import android.content.Context
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Pairing store: a stable device id, a 6-digit pairing code shown on the TV,
 * and the tokens issued to paired Home Assistant instances.
 *
 * Wrong codes are rate limited: after [MAX_ATTEMPTS] bad tries the code is
 * replaced and pairing is locked for [LOCK_MS].
 */
class Pairing(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ar_media_bridge", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private var failedAttempts = 0
    private var lockedUntil = 0L

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }

    val code: String
        get() = prefs.getString(KEY_CODE, null) ?: newCode()

    fun newCode(): String {
        val c = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
        prefs.edit().putString(KEY_CODE, c).apply()
        return c
    }

    val isLocked: Boolean
        get() = System.currentTimeMillis() < lockedUntil

    sealed class Result {
        data class Ok(val token: String) : Result()
        object BadCode : Result()
        object Locked : Result()
    }

    fun tryPair(candidate: String): Result {
        if (isLocked) return Result.Locked
        if (candidate.trim() != code) {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                failedAttempts = 0
                lockedUntil = System.currentTimeMillis() + LOCK_MS
                newCode()
                return Result.Locked
            }
            return Result.BadCode
        }
        failedAttempts = 0
        return Result.Ok(issueToken())
    }

    fun isValid(token: String?): Boolean =
        !token.isNullOrEmpty() && tokens().contains(token)

    val pairedCount: Int
        get() = tokens().size

    /** Forget every paired Home Assistant and issue a new code. */
    fun reset() {
        prefs.edit().remove(KEY_TOKENS).apply()
        newCode()
    }

    private fun tokens(): Set<String> =
        prefs.getStringSet(KEY_TOKENS, emptySet())?.toSet() ?: emptySet()

    private fun issueToken(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putStringSet(KEY_TOKENS, tokens() + token).apply()
        return token
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CODE = "pair_code"
        private const val KEY_TOKENS = "tokens"
        private const val MAX_ATTEMPTS = 5
        private const val LOCK_MS = 60_000L
    }
}
