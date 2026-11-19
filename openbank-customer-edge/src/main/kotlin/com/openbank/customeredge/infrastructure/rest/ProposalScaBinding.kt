// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

/**
 * ADR-0383 D6: the extra dynamic linking a proposal approval adds to the ordinary payment
 * settlement gate, and the gate's report of whether it spent the customer's challenge. One
 * instance per request — never shared — so [consumed] answers for exactly one approval.
 */
class ProposalScaBinding(val linking: Map<String, String>) {
    @Volatile
    var consumed: Boolean = false
}
