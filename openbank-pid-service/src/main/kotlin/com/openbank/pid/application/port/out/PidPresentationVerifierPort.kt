// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.application.port.out

import com.openbank.pid.domain.model.PidClaims

/**
 * Outbound port that cryptographically verifies an EUDI wallet presentation (SD-JWT VC) and returns
 * the verified PID attributes (ADR-0094). The implementation lives in infrastructure (JOSE crypto);
 * the application layer depends only on this port so the dedup decision stays framework-free.
 *
 * Implementations MUST verify, in order, before returning any claim: issuer JWS signature against a
 * configured trusted key (algorithm allow-list pinned), issuer trust, temporal validity (exp/nbf),
 * disclosure-hash binding (every used claim's disclosure hash present in `_sd`), and — when a holder
 * `cnf` key is present — the key-binding JWT. Any failure throws [PidVerificationException]; a returned
 * [PidClaims] therefore means "verified" and is safe for tier-0 deterministic matching.
 */
interface PidPresentationVerifierPort {
    @Throws(PidVerificationException::class)
    fun verify(vpToken: String, nonce: String?, audience: String?): PidClaims
}

/** Raised when an EUDI presentation fails any verification check (mapped to HTTP 422). */
class PidVerificationException(message: String) : RuntimeException(message)
