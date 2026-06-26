// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.crypto

import java.security.PublicKey

/**
 * Exposes the trusted EUDI issuer public keys (the same trust store the SD-JWT verifier uses, static
 * config + the eIDAS Trusted List layer). Used by the mdoc verifier, which — unlike SD-JWT — has no
 * `iss` claim to look up, so it accepts an issuer COSE signature that verifies against ANY trusted key.
 */
interface TrustedIssuerKeys {
    fun allTrustedKeys(): List<PublicKey>
}
