// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.account.contract

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Holds [TaxReportingReplayIdentity] to the committed tax-reporting pact itself, because the
 * broker-sourced replay that can-i-deploy reads never runs on a pull request. Without this, a new
 * tax-reporting interaction can again be replayed under the wrong identity, stay green on the PR,
 * and only fail on the broker after the merge.
 */
class TaxReportingReplayIdentityTest {
    private val pact = File("../pacts/openbank-tax-reporting-service-openbank-account-service.json")

    @Test
    fun `every authorised tax-reporting interaction is replayed as its service account, no refused one is`() {
        val interactions = ObjectMapper().readTree(pact).path("interactions").toList()
        assertThat(interactions).isNotEmpty()

        val (authorised, refused) = interactions.partition { it.path("response").path("status").asInt() != 401 }
        assertThat(authorised).isNotEmpty()
        assertThat(refused).isNotEmpty()

        assertThat(authorised.map { it.path("description").asText() })
            .allMatch { TaxReportingReplayIdentity.appliesTo(it) }
        assertThat(refused.map { it.path("description").asText() })
            .noneMatch { TaxReportingReplayIdentity.appliesTo(it) }
        // The broker twin clears the identity by provider STATE, while this test splits by STATUS.
        // Hold the two to each other, so that a refused replay can never reach the tax branch.
        assertThat(refused.map { i -> i.path("providerStates").map { it.path("name").asText() } })
            .allMatch { NEGATIVE_AUTH_STATE in it }
    }
}
