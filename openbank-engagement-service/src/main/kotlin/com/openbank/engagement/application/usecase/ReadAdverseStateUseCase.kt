// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.application.usecase

import com.openbank.engagement.application.port.out.AdverseStateRepository
import com.openbank.engagement.domain.model.AdverseState
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * The operator-facing read over ADR-0220 D3.5's materialised adverse-state set (issue #4265,
 * item 1). It adds no rule of its own — [AdverseStateRepository.activeStates] already answers
 * exactly this question, and has since the table landed; what was missing was only a way to ask
 * it without a `psql` session.
 *
 * Why a use case rather than the resource calling the port: the resource layer here never touches
 * an out-port directly (`SurfaceResource` goes through `ResolveSurfaceUseCase`/
 * `RecordEngagementEventUseCase`), and the hexagonal boundary is the thing this service's
 * `CLAUDE.md` and ADR-0002 actually gate on.
 *
 * Deliberately NOT returned: `set_at`. The port exposes the state set only, and widening it to
 * carry timestamps would mean a new port method, a new repository query and a second shape for
 * the same fact — for a badge that says "this party is excluded", the set IS the answer. When an
 * operator needs "since when", that is a separate, justified change, not one smuggled in here.
 *
 * ADR-0210 D3 posture is preserved by construction: `party_adverse_state` holds a party id, a flag
 * name and a timestamp — no balance, no transaction row, no KYC content — so this endpoint cannot
 * become the leak vector D3 excludes, regardless of who calls it.
 */
@ApplicationScoped
class ReadAdverseStateUseCase(private val adverseState: AdverseStateRepository) {

    /**
     * Sorted by enum name so the response body is deterministic: the repository returns a `Set`
     * whose iteration order is an implementation detail, and a contract test that asserts a list
     * cannot be allowed to depend on it.
     */
    suspend fun activeStates(partyId: UUID): List<AdverseState> =
        adverseState.activeStates(partyId).sortedBy { it.name }
}
