// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

/**
 * ADR-0341: every banking/retirement schema that backs a pinnable revision carries a closed
 * `accountingClass` and `regulatoryClass`, introduced as a NEW schema version so that revisions
 * published under the older versions stay valid (ADR-0258: published revisions reference their
 * exact schema bytes).
 */
@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_products")],
    restrictToAnnotatedClass = true,
)
@TestSecurity(user = "classification-pack-operator", roles = ["ROLE_OPERATOR"])
class CatalogClassificationPackResourceTest {

    @Inject
    lateinit var mapper: ObjectMapper

    private val regulatory = """{"finrepInstrument":"DEPOSITS_OVERNIGHT","counterpartySector":"PER_PARTY"}"""

    private fun depositBase(extra: String) =
        """{"currency":"CZK","productType":"CURRENT","interest":{"rateType":"FIXED","dayCount":"ACT_365",""" +
            """"payoutFrequency":"MONTHLY","annualRate":"0.5"}$extra}"""

    @Test
    fun `deposit v3 demands both classes while a v2 document stays valid under v2`() {
        val v2 = depositBase("")
        validate(DEPOSIT, 2, v2, true)
        validate(DEPOSIT, 3, v2, false)
        val v3 = depositBase(""","accountingClass":"CUSTOMER_DEPOSIT_DEMAND","regulatoryClass":$regulatory""")
        validate(DEPOSIT, 3, v3, true)
    }

    @Test
    fun `the class vocabularies are closed`() {
        validate(
            DEPOSIT,
            3,
            depositBase(""","accountingClass":"INTERBANK_PLACEMENT","regulatoryClass":$regulatory"""),
            false,
        )
        validate(
            DEPOSIT,
            3,
            depositBase(""","accountingClass":"GL_2100","regulatoryClass":$regulatory"""),
            false,
        )
        validate(
            DEPOSIT,
            3,
            depositBase(
                ""","accountingClass":"CUSTOMER_DEPOSIT_DEMAND",""" +
                    """"regulatoryClass":{"finrepInstrument":"DEPOSITS_OVERNIGHT","counterpartySector":"PER_PARTY",""" +
                    """"glCode":"2100"}""",
            ),
            false,
        )
        validate(
            DEPOSIT,
            3,
            depositBase(
                ""","accountingClass":"CUSTOMER_DEPOSIT_DEMAND",""" +
                    """"regulatoryClass":{"finrepInstrument":"MADE_UP","counterpartySector":"PER_PARTY"}""",
            ),
            false,
        )
    }

    @Test
    fun `loan v3 requires the AnaCredit instrument type and rejects deposit classes`() {
        val base = """{"productType":"INSTALLMENT_LOAN","currency":"CZK","tenorMonths":60,""" +
            """"amortizationMethod":"ANNUITY","nominalAnnualRate":"5.9","accrualBasis":"ACT_365",""" +
            """"allocationOrder":["FEES","PENALTY","INTEREST","PRINCIPAL"]"""
        val ok = """$base,"accountingClass":"LOAN_RETAIL_INSTALLMENT","regulatoryClass":""" +
            """{"finrepInstrument":"LOANS_AND_ADVANCES","counterpartySector":"HOUSEHOLDS",""" +
            """"anacreditInstrumentType":"LOAN"}}"""
        validate(LOAN, 2, "$base}", true)
        validate(LOAN, 3, "$base}", false)
        validate(LOAN, 3, ok, true)
        validate(LOAN, 3, ok.replace(""","anacreditInstrumentType":"LOAN"""", ""), false)
        validate(LOAN, 3, ok.replace("LOAN_RETAIL_INSTALLMENT", "CUSTOMER_DEPOSIT_DEMAND"), false)
    }

    @Test
    fun `treasury instrument v1 describes classification and limits but carries no price`() {
        val ok = """{"instrumentType":"CNB_LOMBARD","counterpartyKind":"CENTRAL_BANK","balanceSide":"LIABILITY",""" +
            """"currencies":["CZK"],"maxTenorDays":1,"accountingClass":"CNB_LOMBARD_BORROWING","regulatoryClass":""" +
            """{"finrepInstrument":"BORROWINGS_AT_AMORTISED_COST","counterpartySector":"CENTRAL_BANK"}}"""
        validate(TREASURY, 1, ok, true)
        validate(TREASURY, 1, ok.replace(""""instrumentType":"CNB_LOMBARD"""", """"instrumentType":"BOND""""), false)
        validate(TREASURY, 1, ok.replace(""""maxTenorDays":1,""", """"rate":"1.5","maxTenorDays":1,"""), false)
        validate(TREASURY, 1, ok.replace("CNB_LOMBARD_BORROWING", "DEPOSIT_X"), false)
    }

    @Test
    fun `pension v3 is the v2 document plus the closed classes`() {
        val seed = seedAttributes()
        validate(PENSION, 2, seed.toString(), true)
        validate(PENSION, 3, seed.toString(), false)
        val v3 = (seed.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode).apply {
            put("accountingClass", "PENSION_SAVINGS_DPS")
            set<JsonNode>("regulatoryClass", mapper.readTree(PENSION_REGULATORY))
        }
        validate(PENSION, 3, v3.toString(), true)
        v3.put("accountingClass", "PENSION_SAVINGS_DIP")
        validate(PENSION, 3, v3.toString(), true)
        v3.put("accountingClass", "CUSTOMER_DEPOSIT_DEMAND")
        validate(PENSION, 3, v3.toString(), false)
    }

    private fun seedAttributes(): JsonNode =
        requireNotNull(javaClass.getResourceAsStream("/catalog-seeds/retirement/cz-illustrative-offerings.json"))
            .use(mapper::readTree)["offerings"][0]["attributes"].let {
            (it.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode).apply {
                putArray("instrumentClasses").add("PENSION_FUNDS")
            }
        }

    private fun validate(schema: String, version: Int, attributes: String, expectedValid: Boolean) {
        Given {
            contentType("application/json")
            body("""{"attributes":$attributes}""")
        } When {
            post("/api/v2/product-types/$schema/versions/$version/validate")
        } Then {
            statusCode(200)
            body("valid", equalTo(expectedValid))
        }
    }

    @Test
    fun `every pack closes accountingClass to the shared vocabulary`() {
        val ids = listOf(
            "banking/deposit-v3",
            "banking/loan-v3",
            "banking/treasury-instrument-v1",
            "retirement/pension-savings-v3",
        )
        val seen = ids.flatMap { id ->
            val schema = requireNotNull(javaClass.getResourceAsStream("/catalog-packs/$id.schema.json"))
                .use(mapper::readTree)
            assertThat(schema["required"].map { it.asText() }).contains("accountingClass", "regulatoryClass")
            schema["properties"]["accountingClass"]["enum"].map { it.asText() }
        }
        assertThat(seen).isSubsetOf(VOCABULARY).doesNotHaveDuplicates()
    }

    private companion object {
        const val DEPOSIT = "org.openbank.banking.deposit"
        const val LOAN = "org.openbank.banking.loan"
        const val TREASURY = "org.openbank.banking.treasury-instrument"
        const val PENSION = "org.openbank.retirement.pension-savings"
        const val PENSION_REGULATORY =
            """{"finrepInstrument":"OFF_BALANCE_DISTRIBUTED_PENSION_PRODUCT","counterpartySector":"HOUSEHOLDS"}"""

        /** Mirrors the ledger's `accounting_class_gl_map` vocabulary; ledger tests hold the other half. */
        val VOCABULARY = setOf(
            "CUSTOMER_DEPOSIT_DEMAND", "CUSTOMER_DEPOSIT_SAVINGS", "CUSTOMER_DEPOSIT_TERM",
            "LOAN_RETAIL_INSTALLMENT", "LOAN_RETAIL_REVOLVING", "LOAN_CORPORATE_INSTALLMENT",
            "INTERBANK_PLACEMENT", "INTERBANK_BORROWING", "CNB_DEPOSIT_FACILITY", "CNB_LOMBARD_BORROWING",
            "FX_SPOT_POSITION", "PENSION_SAVINGS_DPS", "PENSION_SAVINGS_DIP",
        )
    }
}
