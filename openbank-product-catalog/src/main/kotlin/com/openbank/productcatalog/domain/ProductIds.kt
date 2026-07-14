// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import java.util.UUID

/**
 * Canonical product identity derivation (ADR-0105). account-service references a product by a stable
 * UUID (accounts.product_id); the catalogue owns and resolves it. Two products an account opens with
 * adopt fixed sentinel UUIDs (PartyEventConsumer defaults); every other product gets a deterministic
 * name-based UUID of its legacy prod-NNN id, so the same UUID resolves in every environment.
 */
object ProductIds {
    private val sentinels: Map<String, String> = mapOf(
        "prod-014" to "00000000-0000-0000-0000-0000000000c2",
        "prod-010" to "00000000-0000-0000-0000-0000000000c3",
    )

    fun canonicalId(legacyId: String): UUID = sentinels[legacyId]?.let(UUID::fromString)
        ?: UUID.nameUUIDFromBytes("openbank-product:$legacyId".toByteArray())
}
