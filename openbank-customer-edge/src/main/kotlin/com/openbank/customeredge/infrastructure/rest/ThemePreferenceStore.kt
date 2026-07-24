// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import io.quarkus.redis.datasource.RedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Per-party ThemeSpec storage (ADR-0190 §5) — the customer's app-appearance
 * document, roamed across devices.
 *
 * Edge-local Redis rather than a backing service on purpose: a theme is an
 * edge-presentation preference with no domain events, no audit consumers and no
 * cross-service readers, so a dedicated upstream (plus NetworkPolicy, OPA pair
 * and cold-start hop) would be pure overhead. No TTL — a preference, not a
 * session. The client treats a missing/lost value as "use the local copy", so
 * Redis durability is sufficient.
 */
@ApplicationScoped
class ThemePreferenceStore(redis: RedisDataSource) {
    private val values = redis.value(String::class.java)

    fun get(partyId: UUID): String? = values.get(key(partyId))

    fun put(partyId: UUID, specJson: String) {
        values.set(key(partyId), specJson)
    }

    private fun key(partyId: UUID) = "edge:themepref:$partyId"
}
