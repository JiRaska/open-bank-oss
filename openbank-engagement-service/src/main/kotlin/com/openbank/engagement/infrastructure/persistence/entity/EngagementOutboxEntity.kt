// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.engagement.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Outbox row on the kernel v2 base (ADR-0327): `claimed_at` and `next_attempt_at` come from
 * [PanacheOutboxEntityV2]; the table gains them in `V11__outbox_v2.sql`.
 */
@Entity
@Table(name = "engagement_outbox")
class EngagementOutboxEntity : PanacheOutboxEntityV2()
