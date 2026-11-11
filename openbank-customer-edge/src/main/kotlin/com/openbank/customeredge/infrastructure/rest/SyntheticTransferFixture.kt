// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.rest

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

@ApplicationScoped
class SyntheticTransferFixture(
    @ConfigProperty(name = "openbank.synthetic-transfer.party-id")
    private val partyId: Optional<UUID>,
    @ConfigProperty(name = "openbank.synthetic-transfer.source-account-id")
    private val sourceAccountId: Optional<UUID>,
    @ConfigProperty(name = "openbank.synthetic-transfer.target-account-id")
    private val targetAccountId: Optional<UUID>,
) {
    fun accounts(): SyntheticTransferAccounts? {
        val party = partyId.orElse(null) ?: return null
        val source = sourceAccountId.orElse(null) ?: return null
        val target = targetAccountId.orElse(null) ?: return null
        return SyntheticTransferAccounts(party, source, target).takeIf { source != target }
    }
}

data class SyntheticTransferAccounts(val partyId: UUID, val sourceAccountId: UUID, val targetAccountId: UUID)

data class SyntheticTransferRun(val runDate: LocalDate, val direction: SyntheticTransferDirection)

enum class SyntheticTransferDirection { OUTBOUND, RETURN }
