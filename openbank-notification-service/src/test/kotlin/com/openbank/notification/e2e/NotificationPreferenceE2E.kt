// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.notification.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The per-party push preference lifecycle over the real HTTP surface against real Postgres: a
 * party with no row reads all-on, a PUT upserts (and a second PUT replaces), every durable fact
 * is asserted over plain JDBC, and the role boundary pins who may not touch the route.
 */
@QuarkusTest
@TestProfile(NotificationE2EProfile::class)
class NotificationPreferenceE2E : NotificationE2EBase() {

    @Test
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a party with no row reads all-on and persists nothing`() {
        val partyId = UUID.randomUUID()

        val dto = getPreferences(partyId, 200)
        assertThat(dto.get("paymentsPush").asBoolean()).isTrue()
        assertThat(dto.get("productPush").asBoolean()).isTrue()
        assertThat(dto.get("marketingPush").asBoolean()).isTrue()
        // The all-on answer is a DEFAULT, not a stored row.
        assertThat(preferenceRow(partyId)).isNull()
    }

    @Test
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `a PUT upserts and a second PUT replaces the same single row`() {
        val partyId = UUID.randomUUID()

        val first = putPreferences(partyId, mapOf("paymentsPush" to false), 200)
        assertThat(first.get("paymentsPush").asBoolean()).isFalse()
        assertThat(first.get("productPush").asBoolean()).isTrue()
        assertThat(first.get("marketingPush").asBoolean()).isTrue()

        val second = putPreferences(
            partyId,
            mapOf("paymentsPush" to true, "productPush" to false, "marketingPush" to false),
            200,
        )
        assertThat(second.get("paymentsPush").asBoolean()).isTrue()
        assertThat(second.get("productPush").asBoolean()).isFalse()
        assertThat(second.get("marketingPush").asBoolean()).isFalse()

        // Durable, over JDBC: one row, the latest values.
        val row = preferenceRow(partyId)
        assertThat(row).isNotNull
        assertThat(row!!.paymentsPush).isTrue()
        assertThat(row.productPush).isFalse()
        assertThat(row.marketingPush).isFalse()

        // Read-back over HTTP agrees.
        val readBack = getPreferences(partyId, 200)
        assertThat(readBack.get("productPush").asBoolean()).isFalse()
    }

    @Test
    @TestSecurity(user = "notification-e2e-operator", roles = ["ROLE_OPERATOR"])
    fun `preference rows are isolated per party`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()

        putPreferences(partyA, mapOf("marketingPush" to false), 200)
        putPreferences(partyB, mapOf("paymentsPush" to false), 200)

        assertThat(preferenceRow(partyA)!!.marketingPush).isFalse()
        assertThat(preferenceRow(partyA)!!.paymentsPush).isTrue()
        assertThat(preferenceRow(partyB)!!.paymentsPush).isFalse()
        assertThat(preferenceRow(partyB)!!.marketingPush).isTrue()
    }

    @Test
    @TestSecurity(user = "notification-e2e-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer cannot read or set preferences`() {
        getPreferences(UUID.randomUUID(), 403)
        putPreferences(UUID.randomUUID(), mapOf("paymentsPush" to false), 403)
    }

    @Test
    fun `an anonymous caller is refused before any handler`() {
        getPreferences(UUID.randomUUID(), 401)
        putPreferences(UUID.randomUUID(), mapOf("paymentsPush" to false), 401)
    }
}
