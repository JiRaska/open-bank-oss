// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.integration

import com.openbank.domestic.application.port.`in`.DomesticPaymentUseCase
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
import com.openbank.domestic.application.usecase.CzechDomesticIban
import com.openbank.domestic.domain.model.DelegatedSpendReservationSnapshot
import com.openbank.domestic.domain.model.DelegatedSpendReservationState
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.libs.testing.containers.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.path.json.JsonPath
import io.restassured.response.Response
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * #16000 part 3 — the bound delegated create through real HTTP and real PostgreSQL.
 *
 * Every test uses its own reservation, so the assertions read only that reservation's rows.
 */
@QuarkusTest
@TestProfile(DelegatedBoundCreateProfile::class)
@TestSecurity(user = EDGE_USER, roles = ["ROLE_OPERATOR", "ROLE_API"])
@OidcSecurity(
    claims = [
        Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
        Claim(key = "azp", value = "openbank-edge"),
        Claim(key = "preferred_username", value = EDGE_USER),
    ],
)
@QuarkusTestResource(DomesticPaymentOutboxAtomicityIT.NoDispatchInMemoryKafkaResource::class)
@QuarkusTestResource(
    value = PostgresRedisTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_domestic_payment_it")],
)
class DelegatedBoundCreateHttpIT {
    @Inject
    lateinit var bindingRepository: DelegatedSpendBindingRepository

    @Inject
    lateinit var paymentUseCase: DomesticPaymentUseCase

    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun `the edge creates a payment bound to its reservation and every event carries the ids`() {
        val r = reserve()
        val response = post(r)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CREATED)
        val paymentId = UUID.fromString(response.jsonPath().getString("id"))
        assertThat(response.header("X-Idempotency-Replayed")).isNull()

        assertThat(paymentColumns(paymentId)).isEqualTo(listOf(r.delegationId, r.reservationId, r.granteePartyId))
        assertThat(bindingState(r.reservationId)).isEqualTo("BOUND" to paymentId)

        onVertxContext {
            paymentUseCase.transitionStatus(
                TransitionDomesticPaymentStatusCommand(
                    paymentId = paymentId,
                    targetStatus = DomesticPaymentStatus.REJECTED,
                    rejectReason = DomesticRejectReason.values().first(),
                    rejectDetail = "it",
                ),
            )
        }
        val events = outbox(paymentId)
        assertThat(events.map { it.first }).contains("domestic.payment.created", "domestic.payment.status-changed")
        events.forEach { (type, payload) ->
            val json = JsonPath(payload)
            assertThat(json.getString("delegationId")).describedAs(type).isEqualTo(r.delegationId.toString())
            assertThat(json.getString("reservationId")).describedAs(type).isEqualTo(r.reservationId.toString())
        }
    }

    @Test
    fun `an edge retry with the same key replays the one payment`() {
        val r = reserve()
        val first = post(r)
        val second = post(r)
        assertThat(first.statusCode).describedAs(first.body.asString()).isEqualTo(HTTP_CREATED)
        assertThat(second.statusCode).describedAs(second.body.asString()).isEqualTo(HTTP_CREATED)
        assertThat(second.jsonPath().getString("id")).isEqualTo(first.jsonPath().getString("id"))
        assertThat(second.header("X-Idempotency-Replayed")).isEqualTo("true")
        assertThat(paymentsFor(r.reservationId)).isEqualTo(1)
    }

    @Test
    fun `a second payment for the same reservation under another key is refused`() {
        val r = reserve()
        assertThat(post(r).statusCode).isEqualTo(HTTP_CREATED)
        val again = post(r, idempotencyKey = "another-key-${UUID.randomUUID()}")
        assertThat(again.statusCode).describedAs(again.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(paymentsFor(r.reservationId)).isEqualTo(1)
    }

    @Test
    fun `the database itself refuses a second payment row for one reservation`() {
        val r = reserve()
        val paymentId = UUID.fromString(post(r).jsonPath().getString("id"))
        assertThatThrownBy { insertSecondPaymentRow(paymentId, r.reservationId, r.delegationId) }
            .isInstanceOf(SQLException::class.java)
            // The named unique constraint, immediately — not the deferred binding FK at commit,
            // which would also refuse it but is a different control.
            .hasMessageContaining("uq_domestic_payments_reservation_id")
        assertThat(paymentsFor(r.reservationId)).isEqualTo(1)
    }

    @Test
    fun `amount debtor delegation and acting party mismatches are refused with a neutral reason`() {
        val r = reserve()
        val mismatches = listOf(
            post(r, amount = BigDecimal("42.51")),
            post(r, debtorAccountNumber = "1111111111"),
            post(r, delegationId = UUID.randomUUID()),
            post(r, actingPartyId = UUID.randomUUID()),
        )
        mismatches.forEach { response ->
            assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
            assertThat(response.jsonPath().getString("code")).isEqualTo("RESERVATION_MISMATCH")
            assertThat(response.jsonPath().getBoolean("retryable")).isFalse()
            assertThat(response.body.asString()).doesNotContain("amount", "grantee", "42.5")
        }
        assertThat(paymentsFor(r.reservationId)).isZero()
        assertThat(bindingState(r.reservationId).first).isEqualTo("PENDING")
    }

    @Test
    fun `a currency the reservation does not hold is refused`() {
        val r = reserve(currency = "EUR")
        val response = post(r)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("RESERVATION_MISMATCH")
        assertThat(paymentsFor(r.reservationId)).isZero()
    }

    @Test
    fun `a reservation that is no longer RESERVED is refused`() {
        val r = reserve()
        onVertxContext {
            bindingRepository.applySnapshot(
                r.copy(
                    eventId = UUID.randomUUID(),
                    reservationState = DelegatedSpendReservationState.RELEASED,
                    reservationVersion = 2,
                    settledAt = NOW,
                    occurredAt = NOW,
                ),
            )
        }
        val response = post(r)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("RESERVATION_NOT_RESERVED")
        assertThat(paymentsFor(r.reservationId)).isZero()
    }

    @Test
    fun `a reservation the projection has not seen yet is a retryable 409 and writes nothing`() {
        val r = snapshot()
        val response = post(r)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_CONFLICT)
        assertThat(response.jsonPath().getString("code")).isEqualTo("RESERVATION_PROJECTION_PENDING")
        assertThat(response.jsonPath().getBoolean("retryable")).isTrue()
        assertThat(response.header("Retry-After")).isNotBlank()
        assertThat(paymentsFor(r.reservationId)).isZero()
        assertThat(paymentsByKey(KEY_PREFIX + r.reservationId)).isZero()
    }

    @Test
    @TestSecurity(user = CUSTOMER_SUB, roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = CUSTOMER_SUB),
            Claim(key = "azp", value = "openbank-mobile"),
            Claim(key = "preferred_username", value = "jana.customer"),
        ],
    )
    fun `a customer identity is refused before anything is read or written`() = assertForbidden()

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "0b6b2b5e-7a43-4f1c-8a8e-0d1f7c2a9e11"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `another service account is refused`() = assertForbidden()

    @Test
    @TestSecurity(user = EDGE_USER, roles = ["ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "sub", value = "5f0c2a8e-7d1b-4c3e-9a6f-2b8d4e1c7a90"),
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "preferred_username", value = EDGE_USER),
        ],
    )
    fun `a forged edge username on another client is refused`() = assertForbidden()

    private fun assertForbidden() {
        val r = reserve()
        val response = post(r)
        assertThat(response.statusCode).describedAs(response.body.asString()).isEqualTo(HTTP_FORBIDDEN)
        assertThat(paymentsFor(r.reservationId)).isZero()
        assertThat(bindingState(r.reservationId).first).isEqualTo("PENDING")
    }

    // ---------------------------------------------------------------------------------------------

    private fun snapshot(currency: String = "CZK"): DelegatedSpendReservationSnapshot {
        val reservationId = UUID.randomUUID()
        val account = DeterministicAccountLookup.accountOf(DEBTOR_IBAN)
        return DelegatedSpendReservationSnapshot(
            eventId = UUID.randomUUID(),
            reservationId = reservationId,
            delegationId = UUID.randomUUID(),
            grantorPartyId = DeterministicAccountLookup.ownerOf(account),
            granteePartyId = UUID.randomUUID(),
            resourceType = "ACCOUNT",
            resourceId = account,
            amount = AMOUNT,
            currency = currency,
            idempotencyKeyHash = DelegatedSpendReservationSnapshot.hashIdempotencyKey(KEY_PREFIX + reservationId),
            operationType = "DOMESTIC_PAYMENT",
            reservationState = DelegatedSpendReservationState.RESERVED,
            reservationVersion = 1,
            schemaVersion = 1,
            aggregateType = "DelegationSpendReservation",
            sourceService = "delegation-service",
            createdAt = NOW.minusSeconds(60),
            settledAt = null,
            occurredAt = NOW.minusSeconds(60),
        )
    }

    private fun reserve(currency: String = "CZK"): DelegatedSpendReservationSnapshot =
        snapshot(currency).also { onVertxContext { bindingRepository.applySnapshot(it) } }

    @Suppress("LongParameterList")
    private fun post(
        r: DelegatedSpendReservationSnapshot,
        idempotencyKey: String = KEY_PREFIX + r.reservationId,
        amount: BigDecimal = AMOUNT,
        debtorAccountNumber: String = DEBTOR_NUMBER,
        delegationId: UUID = r.delegationId,
        actingPartyId: UUID = r.granteePartyId,
    ): Response = RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(
        mapOf(
            "reservationId" to r.reservationId,
            "delegationId" to delegationId,
            "actingPartyId" to actingPartyId,
            "debtorAccountId" to r.resourceId,
            "debtorAccountNumber" to debtorAccountNumber,
            "debtorBankCode" to DEBTOR_BANK,
            "debtorName" to "Grantor Example",
            "creditorAccountNumber" to "9876543210",
            "creditorBankCode" to "0100",
            "creditorName" to "Brno Utility",
            "amount" to amount,
            "currency" to "CZK",
            "priority" to "STANDARD",
        ),
    ).post("/api/v1/domestic-payments/delegated")

    private fun paymentColumns(paymentId: UUID): List<UUID?> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT delegation_id, reservation_id, initiated_by_party_id FROM domestic_payments WHERE payment_id = ?",
        ).use { s ->
            s.setObject(1, paymentId)
            s.executeQuery().use { rows ->
                check(rows.next())
                listOf(
                    rows.getObject(1, UUID::class.java),
                    rows.getObject(2, UUID::class.java),
                    rows.getObject(3, UUID::class.java),
                )
            }
        }
    }

    private fun bindingState(reservationId: UUID): Pair<String, UUID?> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT binding_state, payment_id FROM domestic_delegated_spend_bindings WHERE reservation_id = ?",
        ).use { s ->
            s.setObject(1, reservationId)
            s.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1) to rows.getObject(2, UUID::class.java)
            }
        }
    }

    private fun outbox(paymentId: UUID): List<Pair<String, String>> = dataSource.connection.use { c ->
        c.prepareStatement("SELECT event_type, payload FROM domestic_payment_outbox WHERE aggregate_id = ?").use { s ->
            s.setObject(1, paymentId)
            s.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
    }

    private fun paymentsFor(reservationId: UUID): Int = countWhere("reservation_id = ?", reservationId)

    private fun paymentsByKey(key: String): Int = countWhere("idempotency_key = ?", key)

    private fun countWhere(predicate: String, value: Any): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM domestic_payments WHERE $predicate").use { s ->
            s.setObject(1, value)
            s.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** Copies the bound row under a new id and key: only the schema stands between it and a commit. */
    private fun insertSecondPaymentRow(sourceId: UUID, reservationId: UUID, delegationId: UUID) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use {
                    it.execute(
                        "CREATE TEMP TABLE dup_row ON COMMIT DROP AS SELECT * FROM domestic_payments WHERE payment_id = '$sourceId'",
                    )
                }
                c.prepareStatement(
                    "UPDATE dup_row SET id = (SELECT max(id) + 1 FROM domestic_payments), " +
                        "payment_id = ?, idempotency_key = ?, end_to_end_id = ?",
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setString(2, "dup-" + UUID.randomUUID())
                    s.setString(3, "DUP-" + reservationId.toString().take(20))
                    s.executeUpdate()
                }
                c.createStatement().use { it.execute("INSERT INTO domestic_payments SELECT * FROM dup_row") }
                c.commit()
            } catch (e: SQLException) {
                c.rollback()
                throw e
            }
        }
        check(delegationId != reservationId)
    }

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        uni(CoroutineScope(Dispatchers.Unconfined)) { block() }
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_CONFLICT = 409
        const val HTTP_FORBIDDEN = 403
        const val KEY_PREFIX = "bound-create-it-"
        const val DEBTOR_NUMBER = "1234567890"
        const val DEBTOR_BANK = "0800"
        val AMOUNT = BigDecimal("42.50")
        val DEBTOR_IBAN: String = checkNotNull(CzechDomesticIban.fromAccountNumber(DEBTOR_NUMBER, DEBTOR_BANK))
        val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    }
}

private const val EDGE_USER = "service-account-openbank-edge"
private const val CUSTOMER_SUB = "7d2c1e4a-3b5f-4a6d-9e8c-1f2a3b4c5d6e"
