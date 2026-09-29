// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.referral.infrastructure.persistence.entity

import com.openbank.libs.persistence.outbox.PanacheOutboxEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * `claimed_at` backs the atomic `FOR UPDATE SKIP LOCKED` claim in
 * [com.openbank.referral.infrastructure.persistence.repository.ReferralOutboxRepositoryImpl] —
 * added straight on this entity rather than the shared [PanacheOutboxEntity] (mapped by every
 * outbox-bearing service; a shared-entity migration would need every service migrated in
 * lockstep). Mirrors `DocumentOutboxEntity`.
 */
@Entity
@Table(name = "referral_outbox")
class ReferralOutboxEntity : PanacheOutboxEntity() {
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null
}
