// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.application.port.out

import com.openbank.sanctions.domain.model.SanctionsListType
import java.util.UUID

enum class SanctionsPublicationOutcome { NO_CHANGES, PUBLISHED, WITHHELD }

/** Publish committed pending changes; retain their evidence on failure or withheld publication. */
interface SanctionsChangePublisher {
    suspend fun publishPending(listId: UUID, listType: SanctionsListType): SanctionsPublicationOutcome
}
