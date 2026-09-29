// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Covers ADR-0212 D2: strict fail-closed parsing and compile-time validation. */
class CompliancePackParserTest {

    private val czPackJson = """
        {
          "jurisdiction": "CZ",
          "productType": "CONSUMER_CREDIT",
          "version": 1,
          "effectiveFrom": "2026-08-01",
          "coolingOffDays": 14,
          "aprDisclosure": { "label": "RPSN", "locale": "cs-CZ" },
          "earlyRepaymentCompensationCap": "0.01",
          "requiredSteps": ["DOCS_REQUIRED"],
          "terminationRules": {
            "noticePeriodDays": 30,
            "permittedGrounds": ["DEFAULT_DPD", "MATERIAL_BREACH"],
            "defaultDpdThreshold": 90
          },
          "disclosures": [
            {
              "id": "secci",
              "templateKey": "cz/secci-v1",
              "languages": ["cs", "en"],
              "requiresAcknowledgement": true,
              "stage": "PRE_CONTRACTUAL"
            }
          ],
          "mandatoryChecks": [
            {
              "id": "cz-dsti-cap",
              "attribute": "DSTI",
              "operator": "LTE",
              "threshold": 0.45,
              "detail": "statutory DSTI cap"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `valid CZ pack parses with all fields`() {
        val pack = CompliancePackParser.fromJson(czPackJson)

        assertThat(pack.jurisdiction).isEqualTo("CZ")
        assertThat(pack.productType).isEqualTo(PackProductType.CONSUMER_CREDIT)
        assertThat(pack.coolingOffDays).isEqualTo(14)
        assertThat(pack.aprDisclosure).isEqualTo(AprDisclosure("RPSN", "cs-CZ"))
        assertThat(pack.earlyRepaymentCompensationCap).isEqualByComparingTo(BigDecimal("0.01"))
        assertThat(pack.terminationRules.defaultDpdThreshold).isEqualTo(90)
        assertThat(pack.disclosures.single().requiresAcknowledgement).isTrue()
        assertThat(pack.mandatoryChecks.single().threshold).isEqualByComparingTo(BigDecimal("0.45"))
    }

    @Test
    fun `unknown key rejects the whole pack (closed schema)`() {
        val json = czPackJson.replace("\"coolingOffDays\": 14,", "\"coolingOffDays\": 14, \"surprise\": true,")

        assertThatThrownBy { CompliancePackParser.fromJson(json) }
            .isInstanceOf(CompliancePackParseException::class.java)
            .hasMessageContaining("unknown key 'surprise'")
    }

    @Test
    fun `unknown enum value rejects with the allowed set in the message`() {
        val json = czPackJson.replace("\"CONSUMER_CREDIT\"", "\"PAYDAY_LOAN\"")

        assertThatThrownBy { CompliancePackParser.fromJson(json) }
            .isInstanceOf(CompliancePackParseException::class.java)
            .hasMessageContaining("PAYDAY_LOAN")
    }

    @Test
    fun `missing mandatory key rejects`() {
        val json = czPackJson.replace("\"coolingOffDays\": 14,", "")

        assertThatThrownBy { CompliancePackParser.fromJson(json) }
            .isInstanceOf(CompliancePackParseException::class.java)
            .hasMessageContaining("coolingOffDays")
    }

    @Test
    fun `unknown origination state in requiredSteps rejects`() {
        val json = czPackJson.replace("\"DOCS_REQUIRED\"", "\"NOT_A_STATE\"")

        assertThatThrownBy { CompliancePackParser.fromJson(json) }
            .isInstanceOf(CompliancePackParseException::class.java)
            .hasMessageContaining("NOT_A_STATE")
    }

    @Test
    fun `unknown policy attribute in mandatoryChecks rejects`() {
        val json = czPackJson.replace("\"DSTI\"", "\"CREDIT_SCORE_VIBE\"")

        assertThatThrownBy { CompliancePackParser.fromJson(json) }
            .isInstanceOf(CompliancePackParseException::class.java)
            .hasMessageContaining("CREDIT_SCORE_VIBE")
    }

    @Test
    fun `compiler rejects mandatory REFLECTION_PERIOD without its duration`() {
        val pack = CompliancePackParser.fromJson(
            czPackJson.replace(
                "\"requiredSteps\": [\"DOCS_REQUIRED\"]",
                "\"requiredSteps\": [\"REFLECTION_PERIOD\"]",
            ),
        )

        assertThatThrownBy { CompliancePackCompiler.compile(pack) }
            .isInstanceOf(CompliancePackValidationException::class.java)
            .hasMessageContaining("reflectionPeriodDays")
    }

    @Test
    fun `compiler rejects compensation cap outside zero to one`() {
        val pack = CompliancePackParser.fromJson(czPackJson.replace("\"0.01\"", "1.5"))

        assertThatThrownBy { CompliancePackCompiler.compile(pack) }
            .isInstanceOf(CompliancePackValidationException::class.java)
            .hasMessageContaining("[0, 1]")
    }

    @Test
    fun `compiler rejects duplicate disclosure ids`() {
        val pack = CompliancePackParser.fromJson(czPackJson).copy(
            disclosures = listOf(
                PackDisclosure("secci", "cz/secci-v1", setOf("cs"), true, DisclosureStage.PRE_CONTRACTUAL),
                PackDisclosure("secci", "cz/other-v1", setOf("cs"), false, DisclosureStage.CONTRACTUAL),
            ),
        )

        assertThatThrownBy { CompliancePackCompiler.compile(pack) }
            .isInstanceOf(CompliancePackValidationException::class.java)
            .hasMessageContaining("duplicate disclosure id")
    }

    @Test
    fun `compiler rejects DPD threshold beyond CRR bounds`() {
        val json = czPackJson.replace("\"defaultDpdThreshold\": 90", "\"defaultDpdThreshold\": 400")

        assertThatThrownBy { CompliancePackCompiler.compile(CompliancePackParser.fromJson(json)) }
            .isInstanceOf(CompliancePackValidationException::class.java)
            .hasMessageContaining("1..180")
    }

    @Test
    fun `compiled pack carries a deterministic content hash and disclosure lookup`() {
        val first = CompliancePackCompiler.compile(CompliancePackParser.fromJson(czPackJson))
        val second = CompliancePackCompiler.compile(CompliancePackParser.fromJson(czPackJson))

        assertThat(first.contentHash).isEqualTo(second.contentHash).hasSize(64)
        assertThat(first.disclosureById["secci"]?.templateKey).isEqualTo("cz/secci-v1")
    }

    @Test
    fun `content hash changes with pack content`() {
        val base = CompliancePackCompiler.compile(CompliancePackParser.fromJson(czPackJson))
        val other = CompliancePackCompiler.compile(
            CompliancePackParser.fromJson(czPackJson.replace("\"coolingOffDays\": 14", "\"coolingOffDays\": 15")),
        )

        assertThat(base.contentHash).isNotEqualTo(other.contentHash)
    }

    @Test
    fun `effectiveness window honours effectiveFrom and effectiveTo`() {
        val pack = CompliancePackParser.fromJson(czPackJson).copy(effectiveTo = LocalDate.parse("2027-01-01"))

        assertThat(pack.isEffectiveOn(LocalDate.parse("2026-07-31"))).isFalse()
        assertThat(pack.isEffectiveOn(LocalDate.parse("2026-08-01"))).isTrue()
        assertThat(pack.isEffectiveOn(LocalDate.parse("2027-01-01"))).isFalse()
    }
}
