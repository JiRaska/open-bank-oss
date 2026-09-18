// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.infrastructure.security

import com.openbank.delegation.application.port.out.DisclosureSecretCodec
import com.openbank.delegation.application.port.out.OtpDigest
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@ApplicationScoped
class Pbkdf2DisclosureSecretCodec : DisclosureSecretCodec {
    private val random = SecureRandom()

    override fun newOpaqueToken(): String = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    override fun newOtp(): String = random.nextInt(OTP_SPACE).toString().padStart(OTP_DIGITS, '0')

    override fun hashOpaqueToken(token: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
    )

    override fun hashOtp(otp: String): OtpDigest {
        require(OTP_PATTERN.matches(otp)) { "OTP must contain exactly six digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return OtpDigest(HexFormat.of().formatHex(salt), derive(otp, salt))
    }

    override fun verifyOtp(otp: String, salt: String, expectedDigest: String): Boolean {
        if (!OTP_PATTERN.matches(otp) || !HEX_SALT.matches(salt) || !HEX_DIGEST.matches(expectedDigest)) return false
        val actual = derive(otp, HexFormat.of().parseHex(salt))
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            expectedDigest.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun derive(otp: String, salt: ByteArray): String {
        val spec = PBEKeySpec(otp.toCharArray(), salt, PBKDF2_ITERATIONS, DIGEST_BITS)
        return try {
            HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
        } finally {
            spec.clearPassword()
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val SALT_BYTES = 16
        const val OTP_DIGITS = 6
        const val OTP_SPACE = 1_000_000
        const val PBKDF2_ITERATIONS = 210_000
        const val DIGEST_BITS = 256
        val OTP_PATTERN = Regex("^[0-9]{6}$")
        val HEX_SALT = Regex("^[0-9a-f]{32}$")
        val HEX_DIGEST = Regex("^[0-9a-f]{64}$")
    }
}
