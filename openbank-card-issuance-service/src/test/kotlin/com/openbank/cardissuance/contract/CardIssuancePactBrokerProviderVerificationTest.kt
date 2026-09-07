// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardissuance.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.cardissuance.infrastructure.persistence.entity.CardEntity
import com.openbank.cardissuance.infrastructure.persistence.repository.CardRepositoryImpl
import com.openbank.cardissuance.it.PostgresRedisTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Broker-side provider verification for card-issuance — the published-result counterpart to
 * [CardIssuancePactFolderProviderVerificationTest].
 *
 * ## Why this exists, and why it had to land in the SAME PR as the pact
 *
 * `can-i-deploy` (ADR-0092) reads the broker and nothing else. A `@PactFolder` class replays the
 * committed pact from disk and never contacts the broker, so a provider carrying only one has
 * never published a version on main, and every consumer of it reads as unverified — permanently,
 * not transiently.
 *
 * card-issuance became a pact PROVIDER for the first time in this PR (delegation-service's
 * ADR-0232 D7 ownership gate reads `GET /api/v1/cards/{id}`). Shipping that pact with only a
 * `@PactFolder` replay would have made `openbank-delegation-service` undeployable as soon as it
 * published: `can-i-deploy` answers "no verified pact between openbank-delegation-service and the
 * version of openbank-card-issuance-service currently in sandbox", reported as `PENDING_BUILD` —
 * a name that reads transient and is not. That is the shape that took fx-service out across four
 * deploy attempts on 2026-08-02, and what #3239 added the aml/sanctions twins for.
 *
 * ## Why a second `@Provider("openbank-card-issuance-service")` class is safe
 *
 * The collision `rules.yaml`/ADR-0029 D3 warns about is two BROKER-sourced classes for one
 * provider, each fetching every pact the broker holds, or an HTTP and a MESSAGE target fighting
 * over the same `@BeforeEach`. This is the sanctioned pair CLAUDE.md documents — `@PactFolder` for
 * the PR lane, `@PactBroker` gated on `pactbroker.url` for the published result — as
 * openbank-ledger-service, aml-service and sanctions-service carry. Note the asymmetry with this
 * module's OTHER pact role: card-issuance is also a CONSUMER (of product-catalog), but a consumer
 * test is not a `@Provider` class and nothing here touches it.
 *
 * Gated on `pactbroker.url`: skipped locally and on the PR lane, where `_service-ci.yml` blanks
 * `PACT_BROKER_URL` (the broker has no public ingress, ADR-0056). It runs on main-push, where
 * `PUBLISH_RESULTS=true` — that run is what creates the main version `can-i-deploy` looks for.
 *
 * ## Every state the git-pact twin handles is handled here too
 *
 * The broker serves EVERY consumer's pact for this provider, not only the one committed under
 * `pacts/`. A missing state fails with `MissingStateChangeMethod` and publishes a FAILURE, which
 * blocks an otherwise healthy pair — strictly worse than publishing nothing. Today that is one
 * state, mirrored verbatim below; anything added to the git-pact twin belongs here in the same
 * commit.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_VIEWER", "ROLE_OPERATOR"])
@Provider("openbank-card-issuance-service")
@PactBroker
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class CardIssuancePactBrokerProviderVerificationTest {

    companion object {
        // Must match DelegationCardOwnershipPactConsumerTest and the git-pact twin.
        private val CARD_ID = UUID.fromString("0a0a0a0a-1b1b-4c2c-8d3d-4e4e4e4e4e4e")
        private val OWNER_PARTY_ID = UUID.fromString("5f5f5f5f-6a6a-4b7b-8c8c-9d9d9d9d9d9d")
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var cardRepository: CardRepositoryImpl

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", testPort.toInt())
        context?.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /**
     * Mirrors [CardIssuancePactFolderProviderVerificationTest.stateCardHeldByKnownParty] verbatim,
     * including the `findById` guard — the broker can hand this class several consumers'
     * interactions naming the same state, so re-entry is expected here rather than hypothetical,
     * and `persist` on an application-assigned id would otherwise collide on the primary key.
     *
     * Written against the entity + Panache directly because `CardRepository.save` demands an
     * `OutboxMessage` this state has no event for, and via `VertxContextSupport.subscribeAndAwait`
     * because Pact-JVM calls `@State` by reflection on the JUnit thread, which carries no Vert.x
     * context — the same bridge `CardDelegationProjectionIT.seedCard` uses from that same thread.
     */
    @State("a card held by a known party exists")
    fun stateCardHeldByKnownParty() {
        val existing = VertxContextSupport.subscribeAndAwait {
            Panache.withSession { cardRepository.find("id", CARD_ID).firstResult() }
        }
        if (existing != null) return
        val entity = CardEntity().apply {
            id = CARD_ID
            idempotencyKey = "pact-$CARD_ID"
            partyId = OWNER_PARTY_ID
            accountId = UUID.fromString("7c7c7c7c-8d8d-4e9e-8f0f-1a1a1a1a1a1a")
            productCode = "DEBIT_BASIC"
            cardType = "VIRTUAL"
            network = "VISA"
            maskedPan = "411111******1111"
            cardholderName = "Pact Verifier"
            embossedName = "PACT VERIFIER"
            expiryDate = LocalDate.of(2030, 1, 31)
            status = "ACTIVE"
            dailyLimitMinorUnits = 100_000
            monthlyLimitMinorUnits = 500_000
            currency = "CZK"
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
            updatedAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        VertxContextSupport.subscribeAndAwait<Unit> {
            Panache.withTransaction { cardRepository.persist(entity).replaceWith(Unit) }
        }
    }

    /**
     * A deliberate no-op: the contract asks for a card id nothing seeds, and the assertion is that
     * the provider answers 404 for it.
     *
     * Declared anyway because Pact fails an interaction whose state has no handler
     * (`MissingStateChangeMethod`), and because an empty method with this comment is the only place
     * a reader learns the emptiness is the point rather than an unfinished fixture.
     */
    @State("no card exists with the unknown id")
    fun stateNoSuchCard() {
        // Nothing to seed. See the KDoc.
    }
}
