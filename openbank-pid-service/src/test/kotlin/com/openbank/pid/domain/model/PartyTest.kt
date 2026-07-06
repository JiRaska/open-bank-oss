// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pid.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit tests for the [Party] aggregate's small role/relationship/external-id helpers.
 * Pure domain — no framework boot.
 */
class PartyTest {

    private val now: OffsetDateTime = OffsetDateTime.parse("2025-01-01T00:00:00Z")

    private fun relationship(
        role: PartyRole,
        status: RelationshipStatus = RelationshipStatus.ACTIVE,
    ) = PartyRelationship(
        id = UUID.randomUUID(),
        partyId = UUID.randomUUID(),
        role = role,
        status = status,
        onboardedAt = now,
        onboardingChannel = OnboardingChannel.API,
        terminatedAt = null,
        terminationReason = null,
    )

    private fun party(
        relationships: List<PartyRelationship> = emptyList(),
        externalIds: List<ExternalId> = emptyList(),
    ) = Party(
        id = UUID.randomUUID(),
        partyType = PartyType.NATURAL_PERSON,
        status = PartyStatus.ACTIVE,
        externalIds = externalIds,
        coreAttributes = CoreAttributes(
            givenName = "Jan",
            familyName = "Novak",
            birthdate = LocalDate.of(1990, 1, 1),
            birthNumberEncrypted = null,
            gender = null,
            birthplace = null,
            nationalities = listOf("CZ"),
            idDocuments = emptyList(),
            verificationSource = VerificationSource.BANKID,
            verifiedAt = now,
        ),
        addressAttributes = null,
        contactAttributes = ContactAttributes(null, null, null, null),
        kycAttributes = KycAttributes(KycLevel.BASIC, null, null, AmlRiskScore.LOW, false, false, null, null),
        relationships = relationships,
        caseLifecycle = null,
        createdAt = now,
        updatedAt = now,
        version = 0,
    )

    @Test
    fun `hasRole is true only for an ACTIVE relationship with that role`() {
        val p = party(relationships = listOf(relationship(PartyRole.CUSTOMER)))
        assertThat(p.hasRole(PartyRole.CUSTOMER)).isTrue()
        assertThat(p.hasRole(PartyRole.EMPLOYEE)).isFalse()
    }

    @Test
    fun `hasRole is false when the matching relationship is TERMINATED`() {
        val p = party(
            relationships = listOf(relationship(PartyRole.CUSTOMER, status = RelationshipStatus.TERMINATED)),
        )
        assertThat(p.hasRole(PartyRole.CUSTOMER)).isFalse()
    }

    @Test
    fun `isCustomer and isEmployee reflect active relationships`() {
        val customer = party(relationships = listOf(relationship(PartyRole.CUSTOMER)))
        assertThat(customer.isCustomer()).isTrue()
        assertThat(customer.isEmployee()).isFalse()

        val employee = party(relationships = listOf(relationship(PartyRole.EMPLOYEE)))
        assertThat(employee.isCustomer()).isFalse()
        assertThat(employee.isEmployee()).isTrue()
    }

    @Test
    fun `activeRelationships filters out non-ACTIVE entries`() {
        val active = relationship(PartyRole.CUSTOMER, status = RelationshipStatus.ACTIVE)
        val terminated = relationship(PartyRole.EMPLOYEE, status = RelationshipStatus.TERMINATED)
        val suspended = relationship(PartyRole.AGENT, status = RelationshipStatus.SUSPENDED)
        val p = party(relationships = listOf(active, terminated, suspended))

        assertThat(p.activeRelationships()).containsExactly(active)
    }

    @Test
    fun `activeRelationships is empty when there are no relationships`() {
        assertThat(party().activeRelationships()).isEmpty()
    }

    @Test
    fun `externalId returns the value for a matching type`() {
        val p = party(externalIds = listOf(ExternalId(ExternalIdType.BANKID_SUB, "sub-1")))
        assertThat(p.externalId(ExternalIdType.BANKID_SUB)).isEqualTo("sub-1")
    }

    @Test
    fun `externalId returns null when the type is not present`() {
        val p = party(externalIds = listOf(ExternalId(ExternalIdType.BANKID_SUB, "sub-1")))
        assertThat(p.externalId(ExternalIdType.ROB_AIFO)).isNull()
    }

    @Test
    fun `externalId returns the first matching entry when duplicates exist`() {
        val p = party(
            externalIds = listOf(
                ExternalId(ExternalIdType.KEYCLOAK_ID, "first"),
                ExternalId(ExternalIdType.KEYCLOAK_ID, "second"),
            ),
        )
        assertThat(p.externalId(ExternalIdType.KEYCLOAK_ID)).isEqualTo("first")
    }
}
