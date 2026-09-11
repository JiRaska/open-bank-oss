// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.application.usecase

import com.openbank.kyb.application.port.`in`.RegistrySearchUseCase
import com.openbank.kyb.application.port.`in`.SearchRegistryCommand
import com.openbank.kyb.application.port.out.BusinessRegistryPort
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.RegistrySearchQuery
import com.openbank.kyb.domain.model.RegistrySearchResult
import com.openbank.kyb.infrastructure.registry.CountryPackRegistry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.LocalDate

/**
 * Name search over a country's public register (issue #9707).
 *
 * Deliberately NOT cached, unlike [RegistryLookupService]: an extract is keyed by an identifier and
 * is worth reusing, while a search is keyed by free text the customer is still typing, so a cache
 * would hold mostly one-hit entries for prefixes nobody repeats. It is also read-only and returns
 * only what the register already publishes, so there is nothing here to keep consistent.
 */
@ApplicationScoped
class RegistrySearchService : RegistrySearchUseCase {

    @Inject lateinit var registry: BusinessRegistryPort

    @Inject lateinit var packs: CountryPackRegistry

    @Inject lateinit var clock: Clock

    override suspend fun search(cmd: SearchRegistryCommand): RegistrySearchResult? {
        val name = cmd.name.trim()
        // Short of the floor we answer EMPTY rather than calling the register: a two-letter query
        // matches tens of thousands of names, which ARES rejects outright, so the round trip buys
        // nothing but load on a public service.
        if (name.length < RegistrySearchQuery.MIN_NAME_LENGTH) return RegistrySearchResult.EMPTY

        val pack = packs.packFor(cmd.country, LocalDate.now(clock)) ?: return null
        // The pack's own scheme list is the routing key, not a hardcoded country->scheme map: a
        // jurisdiction can carry more than one (PL has NIP and KRS) and the pack decides the order.
        val scheme = pack.schemes.firstOrNull { it.country == pack.country } ?: return null

        return registry.search(
            scheme,
            RegistrySearchQuery(
                name = name,
                city = cmd.city?.trim()?.ifBlank {
                    null
                },
                limit = cmd.limit,
            ),
        )
    }

    /** Visible for tests that need the scheme resolution without a register call. */
    internal fun schemeFor(country: String): IdentifierScheme? =
        packs.packFor(country, LocalDate.now(clock))?.let { p -> p.schemes.firstOrNull { it.country == p.country } }
}
