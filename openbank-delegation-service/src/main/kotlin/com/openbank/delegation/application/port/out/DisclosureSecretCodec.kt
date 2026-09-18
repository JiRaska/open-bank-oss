// SPDX-License-Identifier: Apache-2.0
package com.openbank.delegation.application.port.out

data class OtpDigest(val salt: String, val digest: String)

interface DisclosureSecretCodec {
    fun newOpaqueToken(): String
    fun newOtp(): String
    fun hashOpaqueToken(token: String): String
    fun hashOtp(otp: String): OtpDigest
    fun verifyOtp(otp: String, salt: String, expectedDigest: String): Boolean
}
