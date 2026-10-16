// Vaultaire Phase 3: Integrated RFC 6238 / RFC 4226 TOTP Rolling Code Engine
package dev.vaultaire.spike

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * Pure Kotlin RFC 6238 Time-based One-Time Password (TOTP) and RFC 4226 HOTP Engine.
 * Supports standard Base32 secrets (RFC 4648 / Google Authenticator format),
 * 30-second time steps, and dynamic truncation for 6-digit rolling codes.
 */
object TotpEngine {

    private const val DEFAULT_PERIOD = 30
    private const val DEFAULT_DIGITS = 6
    private const val HMAC_ALGO = "HmacSHA1"

    /**
     * Generates a 6-digit TOTP code for the given Base32 secret at the specified epoch time.
     */
    fun generateTotp(
        secretBase32: String,
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        period: Int = DEFAULT_PERIOD,
        digits: Int = DEFAULT_DIGITS
    ): String {
        val cleanSecret = secretBase32.trim().replace(" ", "").replace("-", "").uppercase()
        val keyBytes = try {
            decodeBase32(cleanSecret)
        } catch (e: Exception) {
            return "------"
        }
        if (keyBytes.isEmpty()) return "------"

        val counter = floor(epochSeconds.toDouble() / period).toLong()
        return generateHotp(keyBytes, counter, digits)
    }

    /**
     * Calculates the remaining seconds before the current code rolls over.
     */
    fun getRemainingSeconds(
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        period: Int = DEFAULT_PERIOD
    ): Int {
        val rem = period - (epochSeconds % period).toInt()
        return if (rem == 0) period else rem
    }

    /**
     * Progress ratio from 1.0f (start of period) down to 0.0f (expiration).
     */
    fun getRemainingProgress(
        epochSeconds: Long = System.currentTimeMillis() / 1000L,
        period: Int = DEFAULT_PERIOD
    ): Float {
        val rem = getRemainingSeconds(epochSeconds, period)
        return rem.toFloat() / period.toFloat()
    }

    /**
     * Generates an HOTP code given raw key bytes and a 64-bit counter value.
     */
    fun generateHotp(key: ByteArray, counter: Long, digits: Int = DEFAULT_DIGITS): String {
        try {
            val buffer = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(SecretKeySpec(key, HMAC_ALGO))
            val hash = mac.doFinal(buffer)

            // Dynamic Truncation (RFC 4226 §5.4)
            val offset = (hash[hash.size - 1].toInt() and 0x0F)
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val modulus = when (digits) {
                7 -> 10_000_000
                8 -> 100_000_000
                else -> 1_000_000
            }
            val otp = binary % modulus
            return otp.toString().padStart(digits, '0')
        } catch (e: GeneralSecurityException) {
            return "------"
        }
    }

    /**
     * RFC 4648 Base32 decoding with support for non-padded keys.
     */
    fun decodeBase32(input: String): ByteArray {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().replace("=", "").uppercase()
        if (clean.isEmpty()) return ByteArray(0)

        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()

        for (c in clean) {
            val value = base32Chars.indexOf(c)
            if (value < 0) {
                // Ignore illegal characters or skip
                continue
            }
            buffer = (buffer shl 5) or (value and 0x1F)
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
