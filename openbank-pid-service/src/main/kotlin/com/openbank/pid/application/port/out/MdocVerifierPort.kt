// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import com.openbank.pid.domain.model.PidClaims

/**
 * Cryptographically verifies an ISO 18013-5 mdoc PID credential (CBOR/COSE) and returns the verified
 * claims (ADR-0094). Throws [PidVerificationException] on any failed check. The mdoc alternative to
 * [PidPresentationVerifierPort] (SD-JWT VC); both feed the same tier-0 resolve.
 */
interface MdocVerifierPort {
    fun verify(mdocBase64Url: String): PidClaims
}
