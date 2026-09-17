// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerApprovalGroupsOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
    private val routes = contract.substringAfter("  /delegations/approval-groups:")
        .substringBefore("  /delegations/recertifications:")

    @Test
    fun `every client approval group route is published with a stable operation id`() {
        assertThat(routes).contains(
            "operationId: listDelegationApprovalGroups",
            "operationId: createDelegationApprovalGroup",
            "operationId: delegationApprovalGroupScaReference",
            "operationId: getDelegationApprovalGroup",
            "operationId: reviseDelegationApprovalGroup",
            "operationId: deactivateDelegationApprovalGroup",
        )
        assertThat(routes).contains("The edge derives the owner from the selected profile")
        assertThat(routes).contains("the human actor from the token")
    }

    @Test
    fun `write and read schemas preserve the exact SCA bound roster and revision`() {
        assertThat(contract).contains("DelegationApprovalGroupWrite:")
        assertThat(contract).contains("required: [name, members, threshold, scaSessionId]")
        assertThat(contract).contains("expectedRevision: {type: integer, format: int64, minimum: 1")
        assertThat(contract).contains("DelegationApprovalGroupReferenceRequest:")
        assertThat(contract).contains("DelegationApprovalGroupReference:")
        assertThat(contract).contains("DelegationApprovalGroup:")
        assertThat(contract).contains(
            "required: [id, ownerPartyId, name, members, threshold, revision, active, createdAt, updatedAt]",
        )
    }
}
