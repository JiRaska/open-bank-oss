// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openbank.domestic.application.port.out.AccountStandingLookup
import com.openbank.libs.fincrime.admission.UnavailableCause
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * What the rail makes of an account lookup (ADR-0374 D4). The wire shape is pinned by
 * `AccountStandingPactConsumerTest`; this pins the reading: only an explicit `mayDebit` is an
 * answer, and everything short of it is "could not find out".
 */
class AccountStandingMappingTest {
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun lookup(json: String) = mapper.readValue(json, AccountDto::class.java).toStandingLookup()

    @Test
    fun `an answer without usable standing is unavailable, never may-debit`() {
        val unavailable = AccountStandingLookup.Unavailable(UnavailableCause.MISSING_DATA)
        val party = "\"partyId\":\"$OWNER_PARTY_ID\""
        assertThat(lookup("""{"id":"$ACCOUNT_ID",$party}""")).isEqualTo(unavailable)
        assertThat(lookup("""{"id":"$ACCOUNT_ID",$party,"standing":{}}""")).isEqualTo(unavailable)
        assertThat(lookup("""{"id":"$ACCOUNT_ID",$party,"standing":{"mayCredit":true}}""")).isEqualTo(unavailable)
        assertThat(
            lookup(
                """{"id":"$ACCOUNT_ID",$party,"standing":{"mayDebit":false,"mayCredit":true,"reason":"ACCOUNT_FROZEN"}}""",
            ),
        ).isEqualTo(AccountStandingLookup.Known(UUID.fromString(OWNER_PARTY_ID), false, "ACCOUNT_FROZEN"))
        assertThat(
            lookup("""{"id":"$ACCOUNT_ID",$party,"standing":{"mayDebit":true,"mayCredit":true,"reason":null}}"""),
        )
            .isEqualTo(AccountStandingLookup.Known(UUID.fromString(OWNER_PARTY_ID), true, null))
    }

    private companion object {
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val OWNER_PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    }
}
