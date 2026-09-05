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
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
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
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Git-pact provider verification for card-issuance — the FIRST it has ever had (issue #2991).
 * Until now this module's `build.gradle.kts` said outright that "card issuance publishes no API
 * that another service pacts against"; ADR-0232's ownership gate made that untrue, because
 * delegation-service will not offer a CARD grant without asking card-issuance who holds the card.
 *
 * `@PactFolder`, ungated, so it runs on the pull request: a `@PactBroker` class is
 * `@EnabledIfSystemProperty(pactbroker.url)`-gated and the PR lane has no broker (ADR-0056), which
 * would replay the contract only after the merge — the exact debt #2327 cleared and
 * `check-pact-provider-replay.py` now prevents.
 *
 * ## One HALF of a deliberate pair — the paragraph above is not an argument against the other half
 *
 * [CardIssuancePactBrokerProviderVerificationTest] is the `@PactBroker` +
 * `@EnabledIfSystemProperty(pactbroker.url)` twin, and it is required. This class is the PR-lane
 * gate (zero infra, always runs, catches a wrong route before merge); the twin is the only thing
 * that publishes a verification RESULT, which is all `can-i-deploy` (ADR-0092) reads. A provider
 * carrying only this class has never published a version on main, so every consumer reads as
 * unverified and its deploys block permanently — #3239, and fx-service across four attempts on
 * 2026-08-02. Neither half substitutes for the other.
 *
 * Any `@State` added here MUST be added to the twin in the same commit: a state the broker replay
 * cannot satisfy fails with `MissingStateChangeMethod` and publishes a FAILURE, which is strictly
 * worse than publishing nothing.
 *
 * ## What this replay proves that the consumer pact cannot
 *
 * That `GET /api/v1/cards/{id}` EXISTS and answers with `partyId`. delegation-service's whole
 * ownership verdict is one comparison against that field, and its `catch (Exception)` maps any
 * read failure to UNVERIFIABLE — which refuses the offer. So a renamed field or a moved route is
 * not a crash, it is card delegation quietly ceasing to work. The consumer's Pact mock would keep
 * answering happily; only this class asks the real `CardResource`.
 *
 * `@TestSecurity` matches `CardResource.getCard`'s
 * `@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN")`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestSecurity(user = "pact-verifier", roles = ["ROLE_VIEWER", "ROLE_OPERATOR"])
@Provider("openbank-card-issuance-service")
@PactFolder("../pacts")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class CardIssuancePactFolderProviderVerificationTest {

    companion object {
        // Must match DelegationCardOwnershipPactConsumerTest's CARD_ID / OWNER_PARTY_ID.
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
     * Seeds the row the contract's fixed card id refers to. Written against the entity + Panache
     * directly rather than `CardRepository.save`, which demands an `OutboxMessage` this state has
     * no event for — the same shortcut `CardDelegationProjectionIT.seedCard` takes, and the reason
     * `VertxContextSupport.subscribeAndAwait` appears here: Pact-JVM calls `@State` by reflection
     * on the JUnit thread, which carries no Vert.x context, and this module's ITs already use that
     * bridge from exactly that thread.
     *
     * `findById`-guarded so a second interaction against card-issuance would not collide on the
     * primary key.
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
