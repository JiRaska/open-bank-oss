// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntityV2
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * Outbox row on the kernel v2 base (ADR-0327): `claimed_at` and `next_attempt_at` come from
 * [PanacheOutboxEntityV2]; the table gains them in `V6__outbox_v2.sql`.
 */
@Entity
@Table(name = "referral_outbox")
class ReferralOutboxEntity : PanacheOutboxEntityV2()
