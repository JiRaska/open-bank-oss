// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerApprovalGroupsOpenApiTest {
    private val contract = requireNotNull(javaClass.getResource("/openapi.yaml")).readText()
    private val routes = contract
        .substringAfter("'422': {description: Party eligibility or resource ownership could not be established}")
        .substringBefore("  /profiles:")

    @Test
    fun `every client approval group route is published with a stable operation id`() {
        assertThat(routes).contains(
            "operationId: calculateApprovalGroupScaReference",
            "operationId: listApprovalGroups",
            "operationId: createApprovalGroup",
            "operationId: getApprovalGroup",
            "operationId: reviseApprovalGroup",
            "operationId: deactivateApprovalGroup",
        )
        assertThat(routes).contains("List approval groups for the currently selected profile")
        assertThat(routes).contains("Get an approval group owned by the selected profile")
    }

    @Test
    fun `write and read schemas preserve the exact SCA bound roster and revision`() {
        assertThat(contract).contains("ApprovalGroupWriteRequest:")
        assertThat(contract).contains("required: [name, members, threshold, scaSessionId]")
        assertThat(contract).contains(
            "expectedRevision:\n          type: integer\n          format: int64\n          minimum: 1",
        )
        assertThat(contract).contains("ApprovalGroupScaReferenceRequest:")
        assertThat(contract).contains("required: [name, members, threshold]")
        assertThat(contract).contains("groupId: {type: string, format: uuid, description: Present only for revision}")
    }

    @Test
    fun `approval group contract hides foreign groups and rejects unauthorized writes`() {
        val item = routes.substringAfter("  /delegations/approval-groups/{id}:")
        val create = routes.substringAfter("  /delegations/approval-groups:")
            .substringBefore("  /delegations/approval-groups/{id}:")

        assertThat(item).contains("'404': {description: Missing or belongs to another profile}")
        assertThat(item).contains("'409': {description: expectedRevision is stale}")
        assertThat(create).contains("'400': {description: Invalid roster or SCA}")
    }
}
