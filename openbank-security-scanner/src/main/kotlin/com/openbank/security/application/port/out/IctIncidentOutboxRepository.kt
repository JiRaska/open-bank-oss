// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.security.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository

/** Outbox relay port for durable ICT incident lifecycle events. */
interface IctIncidentOutboxRepository : OutboxRepository
