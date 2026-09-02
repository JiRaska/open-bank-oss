// SPDX-License-Identifier: Apache-2.0
package com.openbank.incentive.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import com.openbank.incentive.it.IncentivePostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

/**
 * Broker-sourced provider verification for incentive-service — the published-result counterpart to
 * [IncentivePactFolderProviderVerificationTest].
 *
 * `@PactFolder` reads pacts off disk: it never contacts the broker, publishes no verification
 * result and creates no provider version. A provider carrying only that half is invisible to
 * `can-i-deploy`, and a broker version row with zero pacts makes the question *unanswerable*
 * rather than negative — every consumer paired with it resolves `UNVERIFIABLE`. document-service
 * sat in exactly that state for 24 days and blocked three consumers, two of them money-path
 * (#7621, fixed by #7738).
 *
 * Preventive rather than corrective: incentive-service has no gitops image pin yet, so nothing is
 * blocked today. Wiring the broker half now means its first deploy arrives with a verifiable
 * contract instead of meeting this gap at the gate.
 *
 * Gated on `pactbroker.url`, so it is skipped locally and on the PR lane — `_service-ci.yml`
 * blanks `PACT_BROKER_URL` because the broker has no public ingress (ADR-0056) — and runs on
 * main-push. The `@PactFolder` sibling stays ungated, so PR-time replay (#2327/#2338) is
 * unchanged.
 *
 * The state handlers below are duplicated from the sibling rather than inherited. A subclass was
 * tried first and rejected on evidence: with parent and subclass both present, Quarkus fails with
 * `TestInstantiationException` and the sibling's own tests go red. Duplication is the shape every
 * other pair in the fleet uses.
 */
@QuarkusTest
@QuarkusTestResource(IncentivePostgresTestResource::class)
@QuarkusTestResource(IncentivePactBrokerProviderVerificationTest.InMemoryKafkaResource::class)
@TestSecurity(user = "maker@openbank.test", roles = ["ROLE_API"])
@Provider("openbank-incentive-service")
@PactBroker(enablePendingPacts = "true")
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
@IgnoreNoPactsToVerify(ignoreIoErrors = "true")
class IncentivePactBrokerProviderVerificationTest {
    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("incentive-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "8081")
    lateinit var testPort: String

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun configureTarget(context: PactVerificationContext?) {
        if (context == null) return
        context.target = HttpTestTarget("localhost", testPort.toInt())
        context.addStateChangeHandlers(this)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun verifyPacts(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a published offer contains the pact promo code for the pact product")
    fun publishedOfferContainsCode() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            deletePreviousReservation(connection)
            if (!offerExists(connection)) insertOffer(connection)
            if (!codeExists(connection)) insertCode(connection)
            connection.commit()
        }
    }

    @State("an attributed reservation is reserved for qualifying commit")
    fun attributedReservationForCommit() = insertReservedAttribution()

    @State("an attributed reservation is reserved for deterministic release")
    fun attributedReservationForRelease() = insertReservedAttribution()

    private fun insertReservedAttribution() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            deletePreviousReservation(connection)
            if (!offerExists(connection)) insertOffer(connection)
            if (!codeExists(connection)) insertCode(connection)
            connection.prepareStatement(INSERT_RESERVATION).use { statement ->
                statement.setString(1, RESERVATION_ID)
                statement.setString(2, OFFER_ID)
                statement.setString(3, codeDigest())
                statement.setString(4, PARTY_ID)
                statement.setString(5, PRODUCT_ID)
                statement.setString(6, ATTRIBUTION_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement("UPDATE promo_code_inventory SET status = 'RESERVED' WHERE digest = ?")
                .use { statement ->
                    statement.setString(1, codeDigest())
                    statement.executeUpdate()
                }
            connection.commit()
        }
    }

    private fun deletePreviousReservation(connection: Connection) {
        connection.prepareStatement(
            "DELETE FROM promo_reservation WHERE id = ?::uuid OR attribution_ref = ?::uuid",
        ).use { statement ->
            statement.setString(1, RESERVATION_ID)
            statement.setString(2, ATTRIBUTION_ID)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE promo_code_inventory SET status = 'AVAILABLE' WHERE digest = ?",
        ).use { statement ->
            statement.setString(1, codeDigest())
            statement.executeUpdate()
        }
    }

    private fun offerExists(connection: Connection): Boolean =
        connection.prepareStatement("SELECT 1 FROM incentive_offer WHERE id = ?::uuid").use { statement ->
            statement.setString(1, OFFER_ID)
            statement.executeQuery().use { it.next() }
        }

    private fun codeExists(connection: Connection): Boolean =
        connection.prepareStatement("SELECT 1 FROM promo_code_inventory WHERE digest = ?").use { statement ->
            statement.setString(1, codeDigest())
            statement.executeQuery().use { it.next() }
        }

    private fun insertOffer(connection: Connection) {
        connection.prepareStatement(INSERT_OFFER).use { statement ->
            statement.setString(1, OFFER_ID)
            statement.setObject(2, java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
            statement.setObject(3, java.sql.Timestamp.from(Instant.now().plusSeconds(86_400)))
            statement.executeUpdate()
        }
    }

    private fun insertCode(connection: Connection) {
        connection.prepareStatement(INSERT_CODE).use { statement ->
            statement.setString(1, codeDigest())
            statement.setString(2, OFFER_ID)
            statement.executeUpdate()
        }
    }

    private fun codeDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest("$PEPPER:$CODE".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val OFFER_ID = "44444444-4444-4444-8444-444444444444"
        const val ATTRIBUTION_ID = "22222222-2222-4222-8222-222222222222"
        const val PARTY_ID = "11111111-1111-4111-8111-111111111111"
        const val PRODUCT_ID = "33333333-3333-4333-8333-333333333333"
        const val RESERVATION_ID = "55555555-5555-4555-8555-555555555555"
        const val CODE = "WELCOME10"
        const val PEPPER = "integration-pepper-with-32-characters-minimum"
        const val INSERT_OFFER = """
            INSERT INTO incentive_offer (
              id, name, version, product_scope, effective_from, expires_at, total_limit,
              per_party_limit, stacking_policy, status, maker, checker, created_at, published_at
            ) VALUES (
              ?::uuid, 'WELCOME', 1, '33333333-3333-4333-8333-333333333333', ?, ?, 10,
              1, 'EXCLUSIVE', 'PUBLISHED', 'maker@openbank.test', 'checker@openbank.test', now(), now()
            )
        """
        const val INSERT_CODE = """
            INSERT INTO promo_code_inventory (digest, offer_id, status, created_at, retained_until)
            VALUES (?, ?::uuid, 'AVAILABLE', now(), now() + interval '13 months')
        """
        const val INSERT_RESERVATION = """
            INSERT INTO promo_reservation (
              id, offer_id, offer_name, offer_version, code_digest, party_ref, product_ref,
              idempotency_key, status, reserved_at, expires_at, attribution_ref
            ) VALUES (
              ?::uuid, ?::uuid, 'WELCOME', 1, ?, ?, ?, 'open-pact-once', 'RESERVED',
              '2026-08-27T00:00:00Z', '2099-01-01T00:00:00Z', ?::uuid
            )
        """
    }
}
