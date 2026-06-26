// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.pid.infrastructure.crypto

/**
 * A trust store whose set of trusted EUDI issuers can be replaced at runtime (ADR-0094). Implemented
 * by the presentation verifier; driven by the eIDAS Trusted List refresher so the trust anchors stay
 * current without a restart. The static `trusted-issuers-json` config remains a permanent base layer.
 */
interface RefreshableTrustStore {
    /**
     * Replace the dynamic trust layer with the issuers in [trustedIssuersJson] (the same
     * `[{"iss":...,"jwks":{...}}]` shape as the static config). Merged on top of the static base —
     * a dynamic entry overrides a static one for the same `iss`. Called only with a list whose
     * signature the refresher has already verified against the trust anchor.
     */
    fun replaceDynamicTrust(trustedIssuersJson: String)
}
