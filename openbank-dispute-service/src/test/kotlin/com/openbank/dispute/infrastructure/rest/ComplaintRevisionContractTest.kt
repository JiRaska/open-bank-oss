// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.infrastructure.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.dispute.domain.model.Complaint
import com.openbank.dispute.domain.model.ComplaintCategory
import com.openbank.dispute.domain.model.ComplaintChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class ComplaintRevisionContractTest {
    @Test
    fun `response and OpenAPI expose the monotonic aggregate revision`() {
        val now = OffsetDateTime.parse("2026-09-13T10:00:00Z")
        val complaint = Complaint(
            reference = "CMP-contract",
            category = ComplaintCategory.PAYMENT_SERVICE,
            channel = ComplaintChannel.APP,
            description = "contract fixture",
            receivedDate = LocalDate.parse("2026-09-13"),
            dueDate = LocalDate.parse("2026-10-02"),
            createdAt = now,
            updatedAt = now,
            aggregateRevision = 7,
        )

        val response = ObjectMapper().findAndRegisterModules()
            .valueToTree<com.fasterxml.jackson.databind.JsonNode>(complaint)
        assertThat(response.path("aggregateRevision").asLong())
            .isEqualTo(7L)
        val contract = checkNotNull(javaClass.getResource("/openapi.yaml")).readText()
        assertThat(contract).contains("aggregateRevision:", "version: 1.5.0")
    }
}
