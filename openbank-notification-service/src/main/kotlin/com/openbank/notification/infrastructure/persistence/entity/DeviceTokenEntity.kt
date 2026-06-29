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

@Entity
@Table(name = "device_tokens")
class DeviceTokenEntity : PanacheEntity() {
    @Column(name = "device_id", nullable = false, unique = true)
    lateinit var deviceId: UUID

    @Column(name = "party_id", nullable = false)
    lateinit var partyId: UUID

    @Column(name = "app_instance", nullable = false)
    lateinit var appInstance: String

    @Column(nullable = false)
    lateinit var platform: String

    @Column(nullable = false, columnDefinition = "TEXT")
    lateinit var token: String

    @Column(name = "app_version")
    var appVersion: String? = null

    @Column(name = "os_version")
    var osVersion: String? = null

    @Column(nullable = false)
    lateinit var status: String

    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null

    @Column(name = "registered_at", nullable = false)
    lateinit var registeredAt: Instant

    @Column(name = "refreshed_at")
    var refreshedAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}
