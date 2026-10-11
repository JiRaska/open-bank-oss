// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.e2e

import com.openbank.pension.e2e.support.PensionTemporalTestEnvironment
import com.openbank.pension.e2e.support.StateAgencySimulator
import com.openbank.pension.infrastructure.exit.stub.StubFundAdministrationAdapter
import com.openbank.pension.infrastructure.exit.stub.StubPayoutPaymentAdapter
import com.openbank.pension.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import io.restassured.path.json.config.JsonPathConfig
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * The whole participant lifecycle of ADR-0334 over real HTTP (issue #12350 slice S7): digital
 * onboarding with questionnaire, KID and SCA signature (S2), contributions and the state incentive
 * claimed and received (S3), transfer-in and strategy change (S1/S2), early termination, lump-sum
 * and phased payout, and death (S5) — with the DIP happy path and the authorisation negatives.
 *
 * Mechanism: the fleet's `*JourneyE2E` shape — `@QuarkusTest`, RestAssured, real Postgres — plus
 * the fleet's in-process Temporal pattern, here registering the REAL workflows
 * ([PensionTemporalTestEnvironment]) so orchestration is exercised, not bypassed.
 *
 * Who plays the other side:
 *  - the ceding provider of a transfer-in is played through the operator relay routes that exist
 *    for exactly that (`counterparty-response`, `funds-received`);
 *  - the state agency is [StateAgencySimulator], answering the filed claim file with a receipt file;
 *  - the fund valuation is seeded into S5's own stub ([StubFundAdministrationAdapter.setValue]),
 *    because no slice binds a valuation to pension-fund-service yet — that seam, and the read of
 *    the stub payment rail ([StubPayoutPaymentAdapter.orders]) to compare executed with quoted
 *    amounts, are the only places this class looks behind the HTTP surface.
 *
 * Customer calls carry `ROLE_API` + `X-Customer-Party-Id` as customer-edge sends them; operator
 * steps run as `ROLE_OPERATOR` under two different principals so four-eyes is real. Steps share
 * state through the companion object and run in [Order].
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(PensionFullLifecycleJourneyE2E.StubbedCollaborators::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass", "TooManyFunctions")
class PensionFullLifecycleJourneyE2E {

    /** Turns on the slices' own fail-closed stubs, as their `%test` profiles intend. */
    class StubbedCollaborators : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.pension.stub-integrations.enabled" to "true",
            "openbank.pension.exit.stub.checks-accept" to "true",
            "openbank.pension.worker.enabled" to "false",
            "openbank.temporal.task-queue" to "openbank-pension-e2e",
        )
    }

    @Inject
    lateinit var temporal: PensionTemporalTestEnvironment

    @Inject
    lateinit var fundValues: StubFundAdministrationAdapter

    @Inject
    lateinit var paymentRail: StubPayoutPaymentAdapter

    companion object {
        // Literals, not randomised: a QuarkusTestProfile loads in another classloader (root CLAUDE.md).
        val customer: UUID = UUID.fromString("0f6d5a3e-1111-4c1e-9a10-000000000001")
        val retiree: UUID = UUID.fromString("0f6d5a3e-1111-4c1e-9a10-000000000002")
        val dipCustomer: UUID = UUID.fromString("0f6d5a3e-1111-4c1e-9a10-000000000003")
        val stranger: UUID = UUID.fromString("0f6d5a3e-1111-4c1e-9a10-000000000099")
        val lastMonth: YearMonth = YearMonth.now().minusMonths(1)

        lateinit var dpsApplication: String
        lateinit var dpsContract: String
        lateinit var dpsReference: String
        var firstPaymentOutcome: String? = null
        var claimBatch: String? = null
        lateinit var dipApplication: String
        lateinit var dipContract: String
        val transferApps = mutableMapOf<String, String>()
        val transferIds = mutableMapOf<String, String>()
        val transferContracts = mutableMapOf<String, String>()
        lateinit var deathClaim: String

        const val JSON = "application/json"
        const val APPS = "/api/v1/pension/onboarding/applications"
        const val FUNDING = "/api/v1/pension/funding/contracts"
        const val OPS = "/api/v1/pension/funding/operations"
        const val PARTY = "X-Customer-Party-Id"
        const val IDEMPOTENCY = "Idempotency-Key"
        const val IBAN = "CZ6508000000192000145399"
        val ORIGINAL_START: LocalDate = LocalDate.of(2010, 1, 1)
        val EXACT: JsonPathConfig =
            JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL)
    }

    // ---- (a) new DPS contract -------------------------------------------------------------------

    @Test
    @Order(1)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `a1 - a new DPS is onboarded through questionnaire, recommendation, KID and SCA signature`() {
        val started = ok(customerPost(customer, "/api/v1/pension/onboarding/applications", dpsStart("1985-05-05")), 201)
        dpsApplication = started.getString("applicationId")
        assertThat(started.getString("status")).isEqualTo("STARTED")

        val q = ok(
            customerPost(
                customer,
                "$APPS/$dpsApplication/questionnaire",
                """{"riskAppetite":2,"lossTolerance":2,"financialSituationStable":true}""",
            ),
        )
        val recommended = q.getString("recommendation.recommendedStrategy")
        assertThat(recommended).describedAs("a 2/2 risk profile 20+ years out").isNotBlank()

        val kid = ok(customerPost(customer, "$APPS/$dpsApplication/strategy", "{}"))
        assertThat(kid.getString("chosenStrategy")).isEqualTo(recommended)
        val documentId = kid.getString("keyInformationDocumentId")
        assertThat(documentId).isNotBlank()

        ok(customerPost(customer, "$APPS/$dpsApplication/kid/accept", """{"documentId":"$documentId"}"""))
        // A refused SCA challenge must not sign.
        assertThat(
            customerPost(customer, "$APPS/$dpsApplication/sign", """{"scaChallengeId":"rejected-1"}""").statusCode,
        )
            .isEqualTo(422)
        val signed =
            ok(
                customerPost(
                    customer,
                    "$APPS/$dpsApplication/sign",
                    """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""",
                ),
            )
        assertThat(signed.getString("status")).isEqualTo("SIGNED")
        assertThat(signed.getString("coolingOffEndsOn")).isNotNull()
        dpsContract = signed.getString("contractId")
        assertThat(dpsContract).isNotNull()

        val contract = ok(customerGet(customer, "/api/v1/pension/contracts/$dpsContract"))
        assertThat(contract.getString("status")).isEqualTo("PENDING_ACTIVATION")
        assertThat(contract.getString("currentStrategy.strategyCode")).isEqualTo(recommended)
        dpsReference = ok(customerGet(customer, "$FUNDING/$dpsContract/payment-reference")).getString("reference")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `a2 - the first contribution paid to the contract reference is credited and activates the contract`() {
        val receipt =
            ok(
                operatorPost(
                    "$OPS/payments",
                    payment("first-${UUID.randomUUID()}", 1700, dpsReference, LocalDate.now()),
                ),
            )
        firstPaymentOutcome = receipt.getString("outcome")
        assertThat(firstPaymentOutcome)
            .describedAs(
                "DPS activates on FIRST_CONTRIBUTION, so the first contribution must be accepted: %s",
                receipt.prettify(),
            )
            .isEqualTo("CREDITED")
        eventually("contract ACTIVE after first contribution") {
            ok(operatorGet("/api/v1/pension/contracts/$dpsContract")).getString("status") == "ACTIVE"
        }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `a3 - a contribution earns the state incentive, which is claimed from and paid by the agency`() {
        activateIfStillSigned(dpsApplication, dpsContract)

        val credited =
            ok(
                operatorPost(
                    "$OPS/payments",
                    payment("m-${UUID.randomUUID()}", 1700, dpsReference, lastMonth.atDay(15)),
                ),
            )
        assertThat(credited.getString("outcome")).describedAs(credited.prettify()).isEqualTo("CREDITED")
        assertThat(credited.getString("contribution.source")).isEqualTo("PARTICIPANT")

        val run = ok(operatorPost("$OPS/claim-runs", """{"period":"$lastMonth"}"""))
        assertThat(run.getInt("claimsCreated")).isGreaterThanOrEqualTo(1)
        val batch = run.getList<Map<String, Any>>("batches")
            .first {
                StateAgencySimulator.parse(it["payload"].toString()).any { c -> c.contractReference == dpsReference }
            }
        claimBatch = batch["id"].toString()
        val filed = StateAgencySimulator.parse(batch["payload"].toString()).filter {
            it.contractReference ==
                dpsReference
        }
        assertThat(filed).hasSize(1)
        assertThat(BigDecimal(filed.single().claimed)).isEqualByComparingTo("340")

        val reconciled = ok(
            operatorPost(
                "$OPS/claim-batches/$claimBatch/receipt",
                """{"payload":${quote(StateAgencySimulator.receipt(batch["payload"].toString()))}}""",
            ),
        )
        assertThat(reconciled.getString("status")).isEqualTo("RECONCILED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `a4 - the participant sees the received incentive and the tax year`() {
        val incentives = ok(customerGet(customer, "$FUNDING/$dpsContract/incentives"))
        assertThat(
            incentives.getString("claims.find { it.incentiveId == 'state-contribution' }.status"),
        ).isEqualTo("RECEIVED")
        assertThat(
            incentives.getObject(
                "balances.find { it.incentiveId == 'state-contribution' }.net",
                BigDecimal::class.java,
            ),
        )
            .isEqualByComparingTo("340")

        val year = ok(customerGet(customer, "$FUNDING/$dpsContract/tax-years/${lastMonth.year}"))
        assertThat(
            year.getObject("participantContributions", BigDecimal::class.java),
        ).isGreaterThanOrEqualTo(BigDecimal("1700"))
        assertThat(year.getObject("stateIncentives", BigDecimal::class.java)).isEqualByComparingTo("340")
    }

    // ---- (f) DIP happy path -------------------------------------------------------------------

    @Test
    @Order(5)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `f1 - a DIP is onboarded under MiFID suitability with an ESG preference`() {
        val started = ok(customerPost(dipCustomer, "/api/v1/pension/onboarding/applications", dipStart()), 201)
        dipApplication = started.getString("applicationId")
        ok(
            customerPost(
                dipCustomer,
                "$APPS/$dipApplication/questionnaire",
                """{"knowledgeLevel":3,"experienceLevel":3,"riskAppetite":3,"lossTolerance":3,
                    "financialSituationStable":true,"esgPreference":"CONSIDER"}""",
            ),
        )
        val kid = ok(customerPost(dipCustomer, "$APPS/$dipApplication/strategy", "{}"))
        ok(
            customerPost(
                dipCustomer,
                "$APPS/$dipApplication/kid/accept",
                """{"documentId":"${kid.getString("keyInformationDocumentId")}"}""",
            ),
        )
        val signed =
            ok(
                customerPost(
                    dipCustomer,
                    "$APPS/$dipApplication/sign",
                    """{"scaChallengeId":"sca-${UUID.randomUUID()}"}""",
                ),
            )
        dipContract = signed.getString("contractId")
        assertThat(
            ok(customerGet(dipCustomer, "/api/v1/pension/contracts/$dipContract")).getString("productLine"),
        ).isEqualTo("DIP")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `f2 - the DIP activates on its first contribution and earns no state incentive`() {
        activateIfStillSigned(dipApplication, dipContract)
        val ref = ok(operatorGet("$FUNDING/$dipContract/payment-reference")).getString("reference")
        assertThat(
            ok(
                operatorPost("$OPS/payments", payment("dip-${UUID.randomUUID()}", 4000, ref, lastMonth.atDay(10))),
            ).getString("outcome"),
        )
            .isEqualTo("CREDITED")
        ok(operatorPost("$OPS/claim-runs", """{"period":"$lastMonth"}"""))
        val incentives = ok(operatorGet("$FUNDING/$dipContract/incentives"))
        assertThat(incentives.getList<Any>("claims")).describedAs("DIP has no state matching contribution").isEmpty()
    }

    // ---- (b) transfer-in, strategy change; also feeds (e) -----------------------------------------

    @Test
    @Order(7)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `b1 - transfer-in applications are signed for three contracts from another provider`() {
        for (key in listOf("lump", "phased", "death")) {
            val started = ok(
                customerPost(retiree, "/api/v1/pension/onboarding/applications", transferStart(key)),
                201,
            )
            val app = started.getString("applicationId")
            ok(
                customerPost(
                    retiree,
                    "$APPS/$app/questionnaire",
                    """{"riskAppetite":1,"lossTolerance":1,"financialSituationStable":true}""",
                ),
            )
            val kid = ok(customerPost(retiree, "$APPS/$app/strategy", "{}"))
            ok(
                customerPost(
                    retiree,
                    "$APPS/$app/kid/accept",
                    """{"documentId":"${kid.getString("keyInformationDocumentId")}"}""",
                ),
            )
            val signed =
                ok(customerPost(retiree, "$APPS/$app/sign", """{"scaChallengeId":"sca-${UUID.randomUUID()}"}"""))
            transferApps[key] = app
            transferIds[key] =
                requireNotNull(signed.getString("transferRequestId")) { "no transfer request: ${signed.prettify()}" }
            transferContracts[key] = signed.getString("contractId")
        }
    }

    @Test
    @Order(8)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `b2 - the ceding provider accepts and the funds arrive with incentive history and original start date`() {
        for ((key, transfer) in transferIds) {
            eventually("transfer $key dispatched") {
                ok(operatorGet("/api/v1/pension/operator/transfers/$transfer")).getString("status") == "SENT"
            }
            ok(
                operatorPost(
                    "/api/v1/pension/operator/transfers/$transfer/counterparty-response",
                    """{"accepted":true}""",
                ),
            )
            eventually("transfer $key accepted") {
                ok(operatorGet("/api/v1/pension/operator/transfers/$transfer")).getString("status") == "ACCEPTED"
            }
            ok(
                operatorPost(
                    "/api/v1/pension/operator/transfers/$transfer/funds-received",
                    """{"amount":250000,"currency":"CZK","originalStartDate":"$ORIGINAL_START",
                        "incentiveHistory":[{"incentiveId":"state-contribution","year":${LocalDate.now().year - 1},"amount":4080}]}""",
                ),
            )
        }
        for ((key, transfer) in transferIds) {
            eventually("transfer $key completed") {
                ok(operatorGet("/api/v1/pension/operator/transfers/$transfer")).getString("status") == "COMPLETED"
            }
        }
    }

    @Test
    @Order(9)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `b3 - the transferred contract is active from its original start date and its strategy can change`() {
        val id = transferContracts.getValue("lump")
        val contract = ok(customerGet(retiree, "/api/v1/pension/contracts/$id"))
        assertThat(contract.getString("status")).isEqualTo("ACTIVE")
        assertThat(
            contract.getString("startDate"),
        ).describedAs("the original start date moves with the transfer").isEqualTo(ORIGINAL_START.toString())

        val changed = given().contentType(
            JSON,
        ).header(PARTY, retiree.toString()).body("""{"strategyCode":"CONSERVATIVE"}""")
            .`when`().put("/api/v1/pension/contracts/$id/strategy")
        assertThat(changed.statusCode).describedAs(changed.body.asString()).isEqualTo(200)
        assertThat(changed.jsonPath().getString("currentStrategy.strategyCode")).isEqualTo("CONSERVATIVE")

        val year = ok(customerGet(retiree, "$FUNDING/$id/tax-years/${LocalDate.now().year}"))
        assertThat(year.getObject("transferIn", BigDecimal::class.java))
            .describedAs("the transferred amount is booked as a TRANSFER_IN contribution")
            .isEqualByComparingTo("250000")
    }

    // ---- (d) early termination -----------------------------------------------------------------

    @Test
    @Order(10)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `d1 - early termination pays exactly the quote, returning the received state incentive`() {
        fundValues.setValue(UUID.fromString(dpsContract), BigDecimal("50000.00"))
        val quoted =
            ok(customerPost(customer, "/api/v1/pension/contracts/$dpsContract/exit/termination/quote", "{}"), 201)
        val notice = quoted.getString("noticeId")
        val net = quoted.getObject("quote.netPayout", BigDecimal::class.java)
        assertThat(quoted.getObject("quote.redemptionValue", BigDecimal::class.java)).isEqualByComparingTo("50000")

        val signed = ok(
            customerPost(
                customer,
                "/api/v1/pension/contracts/$dpsContract/exit/termination/$notice/sign",
                """{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""",
            ),
        )
        assertThat(signed.getString("status")).isEqualTo("SIGNED")
        assertThat(
            ok(customerGet(customer, "/api/v1/pension/contracts/$dpsContract")).getString("status"),
        ).isEqualTo("TERMINATING")

        temporal.advance(Duration.ofDays(31))
        eventually("termination paid") {
            ok(
                customerGet(customer, "/api/v1/pension/contracts/$dpsContract/exit/termination/$notice"),
            ).getString("status") in
                setOf("PAID", "COMPLETED")
        }
        val paid = paymentRail.orders.values.filter { it.contractId == UUID.fromString(dpsContract) }
        assertThat(paid).describedAs("one payout order for the contract").hasSize(1)
        assertThat(paid.single().amount).describedAs("executed == quoted net payout").isEqualByComparingTo(net)

        // The incentive S3 actually received (a3) must be the one S5 returns.
        assertThat(quoted.getObject("quote.incentiveReturn", BigDecimal::class.java))
            .describedAs("incentive return in the quote vs the 340 the agency paid (S3 ledger)")
            .isEqualByComparingTo("340")
    }

    @Test
    @Order(11)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `d2 - the returned incentive is booked back to the agency in the incentive ledger`() {
        val incentives = ok(operatorGet("$FUNDING/$dpsContract/incentives"))
        assertThat(
            incentives.getObject(
                "balances.find { it.incentiveId == 'state-contribution' }.returned",
                BigDecimal::class.java,
            ),
        )
            .describedAs("S3 ledger after S5 termination: %s", incentives.prettify())
            .isEqualByComparingTo("340")
    }

    // ---- (e) regular payout and death --------------------------------------------------------------

    @Test
    @Order(12)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `e1 - a lump sum is paid exactly as quoted once the payout conditions are met`() {
        val id = transferContracts.getValue("lump")
        fundValues.setValue(UUID.fromString(id), BigDecimal("250000.00"))
        val eligibility = ok(customerGet(retiree, "/api/v1/pension/contracts/$id/exit/payout-eligibility"))
        assertThat(eligibility.getBoolean("conditionsMet")).describedAs(eligibility.prettify()).isTrue()
        assertThat(eligibility.getList<String>("allowedForms")).contains("LUMP_SUM", "PHASED_WITHDRAWAL")

        val quote =
            ok(
                customerPost(retiree, "/api/v1/pension/contracts/$id/exit/payouts/quote", """{"form":"LUMP_SUM"}"""),
                201,
            )
        val payout = quote.getString("payoutId")
        ok(
            customerPost(
                retiree,
                "/api/v1/pension/contracts/$id/exit/payouts/$payout/confirm",
                """{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""",
            ),
        )
        eventually("lump sum completed") {
            ok(customerGet(retiree, "/api/v1/pension/contracts/$id/exit/payouts/$payout")).getString("status") ==
                "COMPLETED"
        }
        val statement = ok(customerGet(retiree, "/api/v1/pension/contracts/$id/exit/payouts/$payout/statement"))
        assertThat(
            statement.getObject("netPaid", BigDecimal::class.java),
        ).isEqualByComparingTo(statement.getObject("netQuoted", BigDecimal::class.java))
        assertThat(statement.getObject("netOutstanding", BigDecimal::class.java)).isEqualByComparingTo("0")
        // The statement derives "paid" from the quote, so compare with what actually left on the rail.
        val sent = paymentRail.orders.values.filter { it.contractId == UUID.fromString(id) }
        assertThat(sent).hasSize(1)
        assertThat(sent.single().amount).isEqualByComparingTo(statement.getObject("netQuoted", BigDecimal::class.java))
        assertThat(ok(customerGet(retiree, "/api/v1/pension/contracts/$id")).getString("status")).isEqualTo("PAID_OUT")
    }

    @Test
    @Order(13)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `e2 - a phased withdrawal pays every instalment on its due date`() {
        val id = transferContracts.getValue("phased")
        fundValues.setValue(UUID.fromString(id), BigDecimal("120000.00"))
        val quote = ok(
            customerPost(
                retiree,
                "/api/v1/pension/contracts/$id/exit/payouts/quote",
                """{"form":"PHASED_WITHDRAWAL","months":12}""",
            ),
            201,
        )
        val payout = quote.getString("payoutId")
        assertThat(quote.getInt("months")).isEqualTo(12)
        ok(
            customerPost(
                retiree,
                "/api/v1/pension/contracts/$id/exit/payouts/$payout/confirm",
                """{"scaChallengeId":"sca-${UUID.randomUUID()}","payoutIban":"$IBAN"}""",
            ),
        )
        temporal.advance(Duration.ofDays(400))
        eventually("phased payout completed") {
            ok(customerGet(retiree, "/api/v1/pension/contracts/$id/exit/payouts/$payout")).getString("status") ==
                "COMPLETED"
        }
        val statement = ok(customerGet(retiree, "/api/v1/pension/contracts/$id/exit/payouts/$payout/statement"))
        assertThat(statement.getInt("installmentsTotal")).isEqualTo(12)
        assertThat(statement.getInt("installmentsPaid")).isEqualTo(12)
        assertThat(
            statement.getObject("netPaid", BigDecimal::class.java),
        ).isEqualByComparingTo(statement.getObject("netQuoted", BigDecimal::class.java))
    }

    @Test
    @Order(14)
    @TestSecurity(user = "ops-maker", roles = ["ROLE_OPERATOR"])
    fun `e3 - a death is registered and both beneficiaries are verified`() {
        val id = transferContracts.getValue("death")
        fundValues.setValue(UUID.fromString(id), BigDecimal("100000.00"))
        val notified = ok(
            operatorPost(
                "/api/v1/pension/death-claims",
                """{"contractId":"$id","dateOfDeath":"${LocalDate.now().minusDays(
                    3,
                )}","evidenceRef":"death-cert-e2e"}""",
            ),
            201,
        )
        deathClaim = notified.getString("claimId")
        val claim = ok(
            operatorPut(
                "/api/v1/pension/death-claims/$deathClaim/claimants",
                """{"claimants":[{"name":"Jana Nova","sharePercent":60},{"name":"Petr Novy","sharePercent":40}]}""",
            ),
        )
        for (claimant in claim.getList<Map<String, Any>>("claimants")) {
            ok(
                operatorPost(
                    "/api/v1/pension/death-claims/$deathClaim/claimants/${claimant["claimantId"]}/verification",
                    """{"name":"${claimant["name"]}","birthDate":"1990-01-01","identityDocumentRef":"OP-${claimant["claimantId"]}","iban":"$IBAN"}""",
                ),
            )
        }
        // Four-eyes: the operator who registered the death cannot approve it.
        assertThat(operatorPost("/api/v1/pension/death-claims/$deathClaim/approve", null).statusCode).isIn(403, 409)
    }

    @Test
    @Order(15)
    @TestSecurity(user = "ops-checker", roles = ["ROLE_OPERATOR"])
    fun `e4 - a second operator approves and each beneficiary is paid their share`() {
        val approved = ok(operatorPost("/api/v1/pension/death-claims/$deathClaim/approve", null))
        assertThat(approved.getString("approvedBy")).isEqualTo("ops-checker")
        eventually("death claim settled") {
            ok(operatorGet("/api/v1/pension/death-claims/$deathClaim")).getString("status") == "SETTLED"
        }
        val settled = ok(operatorGet("/api/v1/pension/death-claims/$deathClaim"))
        val claimants = settled.getList<Map<String, Any>>("claimants")
        assertThat(claimants).allSatisfy { assertThat(it["paymentRef"]).isNotNull() }
        val gross = claimants.map { BigDecimal(it["gross"].toString()) }.reduce(BigDecimal::add)
        assertThat(gross).isEqualByComparingTo(
            settled.getObject(
                "valuation",
                BigDecimal::class.java,
            ).subtract(settled.getObject("incentiveReturn", BigDecimal::class.java)),
        )
        assertThat(BigDecimal(claimants.first { it["name"] == "Jana Nova" }["gross"].toString()))
            .isEqualByComparingTo(gross.multiply(BigDecimal("0.6")).setScale(2, java.math.RoundingMode.HALF_EVEN))
    }

    // ---- (f) authorisation negatives -------------------------------------------------------------

    @Test
    @Order(16)
    @TestSecurity(user = "customer-edge", roles = ["ROLE_API"])
    fun `f3 - another customer gets 404 on the onboarding, transfer, funding and exit routes of someone else`() {
        val contract = transferContracts.getValue("phased")
        assertThat(customerGet(stranger, "$APPS/$dpsApplication").statusCode).isEqualTo(404)
        assertThat(customerPost(stranger, "$APPS/$dpsApplication/withdraw", "{}").statusCode).isEqualTo(404)
        assertThat(
            customerGet(stranger, "/api/v1/pension/transfers/${transferIds.getValue("phased")}").statusCode,
        ).isEqualTo(404)
        assertThat(customerGet(stranger, "$FUNDING/$contract/contributions").statusCode).isEqualTo(404)
        assertThat(customerGet(stranger, "$FUNDING/$contract/incentives").statusCode).isEqualTo(404)
        assertThat(
            customerGet(stranger, "/api/v1/pension/contracts/$contract/exit/payout-eligibility").statusCode,
        ).isEqualTo(404)
        assertThat(
            customerPost(
                stranger,
                "/api/v1/pension/contracts/$contract/exit/payouts/quote",
                """{"form":"LUMP_SUM"}""",
            ).statusCode,
        )
            .isEqualTo(404)
        assertThat(
            customerPost(stranger, "/api/v1/pension/contracts/$contract/exit/termination/quote", "{}").statusCode,
        ).isEqualTo(404)
        assertThat(
            customerPost(
                stranger,
                "/api/v1/pension/transfers/out",
                """{"contractId":"$contract","receivingProviderId":"X","receivingProviderName":"X","receivingContractNumber":"1","scaChallengeId":"sca-x"}""",
            ).statusCode,
        ).isEqualTo(404)
        // The customer channel cannot reach operator routes at all.
        assertThat(customerGet(stranger, "/api/v1/pension/operator/transfers").statusCode).isEqualTo(403)
        assertThat(
            customerPost(stranger, "$OPS/payments", payment("x", 1, "x", LocalDate.now())).statusCode,
        ).isEqualTo(403)
    }

    // ---------------------------------------------------------------------------------------------

    /** The operator fallback for a/f when the payment path did not activate the contract (see a2). */
    private fun activateIfStillSigned(application: String, contract: String) {
        if (ok(operatorGet("/api/v1/pension/contracts/$contract")).getString("status") != "ACTIVE") {
            ok(
                operatorPost(
                    "/api/v1/pension/operator/onboarding/applications/$application/contribution-received",
                    null,
                ),
            )
        }
        eventually("contract $contract ACTIVE") {
            ok(operatorGet("/api/v1/pension/contracts/$contract")).getString("status") == "ACTIVE"
        }
    }

    private fun dpsStart(birthDate: String) = """
        {"kind":"NEW_CONTRACT","productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"PENSION_COMPANY","birthDate":"$birthDate","residencyCountry":"CZ",
         "schedule":{"amount":1700,"currency":"CZK","frequency":"MONTHLY"}}
    """.trimIndent()

    private fun dipStart() = """
        {"kind":"NEW_CONTRACT","productLine":"DIP","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"BANK","birthDate":"1979-03-14",
         "schedule":{"amount":4000,"currency":"CZK","frequency":"MONTHLY"}}
    """.trimIndent()

    private fun transferStart(key: String) = """
        {"kind":"TRANSFER_IN","productLine":"DPS","jurisdiction":"CZ","providerEntityId":"${UUID.randomUUID()}",
         "providerType":"PENSION_COMPANY","birthDate":"1960-02-02","residencyCountry":"CZ",
         "schedule":{"amount":1000,"currency":"CZK","frequency":"MONTHLY"},
         "transferIn":{"providerId":"ceding-ps","providerName":"Ceding Pension Company","contractNumber":"CED-$key"}}
    """.trimIndent()

    private fun payment(id: String, amount: Int, reference: String, valueDate: LocalDate) =
        """{"paymentId":"$id","amount":$amount,"currency":"CZK","valueDate":"$valueDate","reference":"$reference"}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun customerPost(party: UUID, path: String, body: String): Response = given().contentType(JSON)
        .header(PARTY, party.toString()).header(IDEMPOTENCY, UUID.randomUUID().toString())
        .body(body).`when`().post(path)

    private fun customerGet(party: UUID, path: String): Response =
        given().header(PARTY, party.toString()).`when`().get(path)

    private fun operatorPost(path: String, body: String?): Response = given().contentType(JSON)
        .header(IDEMPOTENCY, UUID.randomUUID().toString())
        .let { if (body != null) it.body(body) else it }
        .`when`().post(path)

    private fun operatorPut(path: String, body: String): Response =
        given().contentType(JSON).body(body).`when`().put(path)

    private fun operatorGet(path: String): Response = given().`when`().get(path)

    private fun ok(response: Response, status: Int = 200): JsonPath {
        assertThat(response.statusCode).describedAs("%s", response.body.asString()).isEqualTo(status)
        return response.jsonPath(EXACT)
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
