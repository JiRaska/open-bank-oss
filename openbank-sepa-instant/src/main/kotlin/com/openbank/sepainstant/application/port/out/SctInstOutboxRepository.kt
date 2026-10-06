// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sepainstant.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository

interface SctInstOutboxRepository : OutboxRepository {
    /** Terminal DEAD rows are excluded from the processable backlog. */
    suspend fun countDead(): Long
}
