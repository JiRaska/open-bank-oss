// SPDX-License-Identifier: Apache-2.0
@file:Suppress("PackageName")

package com.openbank.delegation.application.port.`in`

import com.openbank.delegation.domain.model.Disclosure
import java.util.UUID

data class PrepareDisclosureCommand(val requestId: UUID, val delegationId: UUID, val callerPartyId: UUID?)

interface PrepareDisclosureUseCase {
    suspend fun prepare(command: PrepareDisclosureCommand): Disclosure
}

interface GetDisclosureUseCase {
    suspend fun get(id: UUID, callerPartyId: UUID?): Disclosure
}
