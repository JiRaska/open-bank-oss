// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.party.application.port.out.PartyModification
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.application.port.out.PartyWrite
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyEvent
import io.mockk.CapturingSlot
import io.mockk.coEvery
import java.util.UUID

/**
 * Stubs [PartyRepository.modify] on a mock the way the real one behaves: the change is applied to
 * the party the mock's `findById` currently returns (standing in for the locked row), and what it
 * writes is captured into [updated] / [event]. Null when `findById` has no such party.
 */
internal fun stubModify(repo: PartyRepository, updated: CapturingSlot<Party>, event: CapturingSlot<PartyEvent>) {
    coEvery { repo.modify(any(), any()) } coAnswers {
        val current = repo.findById(firstArg<UUID>())
        if (current == null) {
            null
        } else {
            val write = secondArg<(Party) -> PartyWrite>()(current)
            updated.captured = write.party
            event.captured = write.event
            PartyModification(current, write.party)
        }
    }
}
