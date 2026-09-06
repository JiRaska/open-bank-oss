// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.analytics.DataCategory
import com.openbank.libs.analytics.RetentionPolicies
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `openbank.kyb.events` in the analytics stream (ADR-0284 D8) — the business half of the customer
 * graph. Three things have to hold together, and each is invisible from the other two: the topic
 * is subscribed, the record is attributable, and a natural person's name never lands in the clear.
 */
class KybAnalyticsIngestTest {

    private val json = ObjectMapper()

    @Test
    fun `the kyb topic is subscribed and carries a Read ACL`() {
        // Two files, one fact. Subscribing without the ACL is a GroupAuthorizationException on
        // every poll; the ACL without the subscription is a grant to read nothing. Neither half
        // reports the other missing, which is why they are asserted together.
        val config = File("src/main/resources/application.yaml").readText()
        assertThat(config).contains("openbank.kyb.events")

        val acl = File("../openbank-infra/gitops/components/analytics/kafka-analytics-sink-mtls.yaml").readText()
        assertThat(acl).contains("name: openbank.kyb.events")
    }

    @Test
    fun `a kyb record attributes to the kyb service without an override`() {
        assertThat(TopicAttribution.domainOf("openbank.kyb.events")).isEqualTo("kyb")
        assertThat(TopicAttribution.aggregateType("openbank.kyb.events")).isEqualTo("KYB")
        assertThat(TopicAttribution.sourceService("openbank.kyb.events")).isEqualTo("openbank-kyb-service")
    }

    @Test
    fun `KYB is retained on the AML basis, not the accounting default`() {
        // The default would hold it for the same period, so this assertion is about the declared
        // basis rather than the outcome: an unclassified aggregate reads exactly like a
        // classified one in every retention report, which is how it would stay unclassified.
        assertThat(RetentionPolicies.categoryForAggregateType("KYB")).isEqualTo(DataCategory.KYC)
        assertThat(RetentionPolicies.of(DataCategory.KYC).erasable).isFalse()
    }

    @Test
    fun `a signer name and a sole trader legal name are masked, the identifier is not`() {
        val payload = json.readTree(
            """
            {"eventType":"BUSINESS_SIGNER_INVITED","caseId":"11111111-1111-1111-1111-111111111111",
             "status":"AWAITING_COSIGNERS","identifierScheme":"CZ_ICO","identifier":"27074358",
             "legalName":"Jan Novák","signerName":"Eva Dvořáková","country":"CZ","signedCount":1}
            """.trimIndent(),
        )

        val masked = PayloadMasker.maskToMap(payload)

        // A sole trader's legal name IS their own name, and no field-name-keyed rule can tell that
        // apart from a company name — so both are masked, in both cases.
        assertThat(masked["legalName"] as String).isNotEqualTo("Jan Novák")
        assertThat(masked["signerName"] as String).isNotEqualTo("Eva Dvořáková")
        // The identifier is what analytics actually joins on, and it is not personal data for a
        // company. Masking it would cost the whole point of ingesting the stream.
        assertThat(masked["identifier"]).isEqualTo("27074358")
        assertThat(masked["caseId"]).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(masked["status"]).isEqualTo("AWAITING_COSIGNERS")
    }
}
