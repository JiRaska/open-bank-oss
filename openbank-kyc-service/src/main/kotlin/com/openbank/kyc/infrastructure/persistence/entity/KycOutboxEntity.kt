// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.kyc.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Kyc's outbox row on the kernel outbox v2 base (ADR-0327): `claimed_at` and `next_attempt_at`
 * come from [PanacheOutboxEntityV2], written by `AbstractPanacheOutboxRepository`'s claim and
 * backoff SQL (migration `V11__outbox_v2.sql`).
 */
@Entity
@Table(name = "kyc_outbox")
class KycOutboxEntity : PanacheOutboxEntityV2()
