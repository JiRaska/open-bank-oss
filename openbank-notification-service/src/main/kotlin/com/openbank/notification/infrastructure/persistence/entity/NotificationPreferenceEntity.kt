// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.infrastructure.persistence.entity

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A party's push-notification preferences (#2). One row per party; a missing row means "all on"
 * (the app never has to seed defaults). Security-critical notifications (OTP, SCA, KYC, account
 * freeze) are NOT represented here — they are always delivered regardless of these flags.
 */
@Entity
@Table(name = "notification_preferences")
class NotificationPreferenceEntity : PanacheEntity() {
    @Column(name = "party_id", nullable = false, unique = true)
    lateinit var partyId: UUID

    @Column(name = "payments_push", nullable = false)
    var paymentsPush: Boolean = true

    @Column(name = "product_push", nullable = false)
    var productPush: Boolean = true

    @Column(name = "marketing_push", nullable = false)
    var marketingPush: Boolean = true

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
