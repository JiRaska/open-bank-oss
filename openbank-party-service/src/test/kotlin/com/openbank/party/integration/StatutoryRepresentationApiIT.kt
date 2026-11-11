// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.audit.decision.InputDigest
import com.openbank.libs.testing.containers.PostgresRedpandaTestResource
import com.openbank.party.application.port.`in`.GrantMandateCommand
import com.openbank.party.application.port.`in`.PartyUseCase
import com.openbank.party.application.port.`in`.RevokeMandateCommand
import com.openbank.party.application.port.out.PartyRepository
import com.openbank.party.application.usecase.StatutoryRepresentationService
import com.openbank.party.domain.model.JointRepresentationMode
import com.openbank.party.domain.model.KycStatus
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.Party
import com.openbank.party.domain.model.PartyStatus
import com.openbank.party.domain.model.PartyType
import com.openbank.party.domain.model.StatutoryRepresentationEvidence
import com.openbank.party.domain.model.StatutoryRepresentative
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle
import io.restassured.RestAssured.given
import io.vertx.core.Vertx
import io.vertx.core.impl.ContextInternal
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(
    value = PostgresRedpandaTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_party_it")],
    restrictToAnnotatedClass = true,
)
class StatutoryRepresentationApiIT {
    @Inject lateinit var repository: PartyRepository

    @Inject lateinit var party: PartyUseCase

    @Inject lateinit var service: StatutoryRepresentationService

    @Inject lateinit var mapper: ObjectMapper

    @Inject lateinit var vertx: Vertx

    @Test
    @TestSecurity(user = "service-account-openbank-kyb", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "fixture-kyb-subject"),
            Claim(key = "azp", value = "openbank-kyb"),
            Claim(key = "preferred_username", value = "service-account-openbank-kyb"),
        ],
    )
    fun `verified KYB publishes and retries the same durable rule`() {
        val company = UUID.randomUUID()
        val evidence = evidence()
        seed(company, evidence)
        val mismatched = evidence.copy(
            representatives = evidence.representatives.mapIndexed { index, member ->
                member.copy(mandateEvidenceHash = evidence.representatives[1 - index].mandateEvidenceHash)
            },
        )
        given().contentType("application/json").body(mapper.writeValueAsString(mismatched))
            .put("/api/v1/parties/$company/statutory-representation").then().statusCode(400)

        repeat(2) {
            given().contentType("application/json").body(mapper.writeValueAsString(evidence))
                .put("/api/v1/parties/$company/statutory-representation").then()
                .statusCode(200)
                .body("principalPartyId", equalTo(company.toString()))
                .body("evidence.attestationId", equalTo(evidence.attestationId.toString()))
                .body("evidence.requiredSignatures", equalTo(2))
        }
    }

    @Test
    @TestSecurity(user = "service-account-openbank-delegation", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "fixture-delegation-subject"),
            Claim(key = "azp", value = "openbank-delegation"),
            Claim(key = "preferred_username", value = "service-account-openbank-delegation"),
        ],
    )
    fun `verified delegation reader receives evidence only while the recorded mandates are active`() {
        val company = UUID.randomUUID()
        val evidence = evidence()
        seed(company, evidence)
        onVertx { service.publish(company, evidence) }

        val route = "/api/v1/parties/$company/statutory-representation"
        given().get(route).then().statusCode(200)
            .header("Cache-Control", equalTo("no-store"))
            .body("evidence.attestationId", equalTo(evidence.attestationId.toString()))

        onVertx {
            val mandate = party.listMandates(company).first()
            party.revokeMandate(
                RevokeMandateCommand(company, mandate.id, "fixture revocation"),
            )
        }
        given().get(route).then().statusCode(404)
            .header("Cache-Control", equalTo("no-store"))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "fixture-shared-subject"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `the shared service identity is denied before evidence is read or published`() {
        val route = "/api/v1/parties/${UUID.randomUUID()}/statutory-representation"
        given().get(route).then().statusCode(403)
        given().contentType("application/json").body(mapper.writeValueAsString(evidence()))
            .put(route).then().statusCode(403)
    }

    private fun evidence(): StatutoryRepresentationEvidence {
        val caseId = UUID.randomUUID()
        return StatutoryRepresentationEvidence(
            caseId = caseId,
            attestationId = UUID.randomUUID(),
            ruleTextHash = "a".repeat(64),
            attestedAt = Instant.now().minusSeconds(60),
            mode = JointRepresentationMode.JOINT_ALL,
            requiredSignatures = 2,
            requiredOffices = listOf("chair", "member"),
            representatives = (0..1).map { office ->
                val actor = UUID.randomUUID()
                val digest = InputDigest.sha256(
                    mapOf("evidenceRef" to fixtureEvidenceRef(caseId), "representativePartyId" to actor.toString()),
                )
                StatutoryRepresentative(actor, setOf(office), digest.hex)
            },
        )
    }

    private fun seed(company: UUID, evidence: StatutoryRepresentationEvidence) = onVertx {
        repository.save(partyFixture(company, PartyType.COMPANY))
        evidence.representatives.forEach { representative ->
            repository.save(partyFixture(representative.partyId, PartyType.INDIVIDUAL))
            party.grantMandate(
                GrantMandateCommand(
                    principalPartyId = company,
                    agentPartyId = representative.partyId,
                    role = MandateRole.LEGAL_REPRESENTATIVE,
                    authority = MandateAuthority.JOINT,
                    requiredSignatures = 2,
                    source = MandateSource.REGISTRY,
                    evidenceRef = fixtureEvidenceRef(evidence.caseId),
                ),
            )
        }
    }

    private fun fixtureEvidenceRef(caseId: UUID) = "kyb-case:$caseId:signature:db1bd1c2-8f5a-438e-b8b4-380bc78a3c42"

    private fun partyFixture(id: UUID, type: PartyType) = Party(
        id = id,
        partyType = type,
        status = PartyStatus.ACTIVE,
        legalName = "Statutory Test Fixture",
        tradingName = null,
        dateOfBirth = null,
        nationality = null,
        taxId = null,
        registrationNumber = null,
        email = "$id@example.invalid",
        phone = null,
        address = null,
        kycStatus = KycStatus.APPROVED,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun onVertx(block: suspend () -> Unit) {
        val done = CompletableFuture<Unit>()
        val context = (vertx.orCreateContext as ContextInternal).duplicate()
        VertxContextSafetyToggle.setContextSafe(context, true)
        val executor = Executor { command -> context.runOnContext { command.run() } }
        CoroutineScope(executor.asCoroutineDispatcher()).launch {
            runCatching { block() }.onSuccess { done.complete(Unit) }.onFailure { done.completeExceptionally(it) }
        }
        done.get(30, TimeUnit.SECONDS)
    }
}
