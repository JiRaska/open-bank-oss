// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.fincrime

/**
 * fraud-service's verdict (ADR-0084 §1), shared with the rails that ask for it (ADR-0353 D4).
 *
 * [UNKNOWN] is what a CONSUMER reads when fraud-service answered with a verdict it does not
 * recognise, or with none at all (#4403). It is deliberately not [ALLOW]: a response that could not
 * be read says nothing about the payment, and an enforcing caller must never read it as a clean
 * score. fraud-service never produces it and refuses to put it on the wire.
 */
enum class FraudVerdict {
    ALLOW,
    CHALLENGE,
    REVIEW,
    DECLINE,
    UNKNOWN,
    ;

    companion object {
        /** Total, fail-closed parse of a wire value: absent or unrecognised is [UNKNOWN], never [ALLOW]. */
        fun fromWire(value: String?): FraudVerdict {
            val parsed = entries.firstOrNull { it.name == value?.trim()?.uppercase() }
            return if (parsed == null || parsed == UNKNOWN) UNKNOWN else parsed
        }
    }
}
