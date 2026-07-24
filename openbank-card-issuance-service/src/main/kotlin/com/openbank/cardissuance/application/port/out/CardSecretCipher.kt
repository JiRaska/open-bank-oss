// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.application.port.out

/**
 * Outbound port for encrypting the synthetic PAN/CVV at rest (#3). The application layer only ever
 * sees opaque strings; the algorithm, the key source and the IV handling belong to the adapter
 * (`AesGcmCardSecretCipher`). Keeping this a port also means the use-case tests can round-trip
 * through a real cipher without booting Quarkus.
 */
interface CardSecretCipher {
    /** Returns base64(IV ‖ ciphertext ‖ tag) for [plaintext]. */
    fun encrypt(plaintext: String): String

    /** Inverse of [encrypt]. Throws if the ciphertext was tampered with or the key changed. */
    fun decrypt(ciphertext: String): String
}
