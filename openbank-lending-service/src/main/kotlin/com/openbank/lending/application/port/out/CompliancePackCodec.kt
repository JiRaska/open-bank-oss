// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.port.out

import com.openbank.libs.lending.compliance.CompliancePack

/**
 * Outbound port (ADR-0002 hexagonal boundary) for decoding a serialized compliance pack into the
 * domain [CompliancePack] shape. The application layer depends on this interface only; the wire
 * format (JSON today) is an infrastructure adapter detail behind it.
 */
interface CompliancePackCodec {
    /** Strict, fail-closed: a malformed document or an unknown key rejects the pack whole. */
    fun fromJson(json: String): CompliancePack
}
