// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.standingorder.infrastructure.kafka

import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class KafkaStandingOrderEventPublisher {
    suspend fun publishCreated(id: java.util.UUID) {}
    suspend fun publishExecuted(id: java.util.UUID) {}
    suspend fun publishCancelled(id: java.util.UUID) {}
}
