// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.sdd.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #8353 — proves that `SddMandateService`'s two write paths commit the `sdd_mandate` row and
 * its `sdd_outbox` row in **one** database transaction, so neither can exist without the other:
 * `register` (`POST /api/v1/sdd/mandates`, the birth of a debtor mandate) and the lifecycle
 * `transition` helper behind `suspend`/`resume`/`cancel`/`confirm`
 * (`POST /api/v1/sdd/mandates/{id}/suspend`).
 *
 * A mandate is the standing authority under which `SddCollectionDebitConsumer` posts a real debit,
 * so a mandate whose state change committed without its event — or the reverse — is a divergence on
 * the money path itself.
 *
 * ### Why presence is not the property
 *
 * The house pattern (`LendingOutboxWriteIT`, `ConsentRevocationOutboxIT`) drives the flow through
 * the real REST endpoint and asserts the outbox row landed. That is necessary — a mocked repository
 * commits nothing, and a reactive Hibernate repo cannot be driven from a bare `@QuarkusTest` thread
 * ("No current Vertx context found"), so only a real HTTP request can exercise the write — but it
 * is **not sufficient**: an implementation that persisted the aggregate in one transaction and the
 * outbox row in a second would satisfy every presence assertion while having lost the property.
 * The sibling [SddOutboxDispatchIT] seeds outbox rows directly and tests claim/dispatch semantics,
 * so it is silent about the write.
 *
 * ### What makes it falsifiable
 *
 * Postgres stamps every row version with `xmin`, the id of the transaction that wrote it. Two rows
 * written by one transaction carry the *same* `xmin`; two rows written by two transactions cannot.
 * Here the single transaction is the resource method's own `@WithTransaction`, which
 * `SddMandateRepositoryImpl.save`'s `sf.withTransaction` and `SddOutboxRepositoryImpl.append`'s
 * bare `persist` both join; splitting either out of it turns this test red, where a presence
 * assertion stays green.
 *
 * The scheduled outbox dispatcher is switched off for this class: it UPDATEs claimed rows, and an
 * UPDATE writes a new row version with a *new* `xmin`, which would race the assertion. sdd ships
 * `openbank.outbox.dispatch-enabled: true` in BOTH its default and its `%test` profile (the latter
 * so [SddOutboxDispatchIT] can drive `dispatch()` directly), so leaving it alone here would not do.
 *
 * That switch lives in a [QuarkusTestProfile] and NOT in the test resource, deliberately: a
 * `@QuarkusTestResource` is applied to every test class in the module, so putting
 * `dispatch-enabled=false` there turned [SddOutboxDispatchIT] red — measured, not assumed. A
 * profile is per-class and forces this class its own Quarkus boot, which is exactly the scope
 * wanted.
 */
@QuarkusTest
@TestProfile(SddOutboxAtomicityIT.NoDispatchProfile::class)
@QuarkusTestResource(SddOutboxAtomicityIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
class SddOutboxAtomicityIT {

    /**
     * `authz.enforce` is the second override and is not incidental: sdd is one of the few services
     * shipping `AUTHZ_ENFORCE` defaulted to **true** (account and fraud both default it to false),
     * and with no OPA sidecar reachable the interceptor fails closed — every write endpoint here
     * answers **503**, not 403, so without this the test would be red for a reason that has nothing
     * to do with the outbox.
     */
    class NoDispatchProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
    }

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("sdd-events-out").toMutableMap()
            props.putAll(InMemoryConnector.switchIncomingChannelsToInMemory("sdd-collection-authorised-in"))
            props["quarkus.kafka.devservices.enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = ACTOR_ID, roles = ["ROLE_OPERATOR"])
    fun `both mandate write paths commit the mandate row and its outbox row in one transaction`() {
        val mandateId = registerCoreMandate()

        // --- write path 1: register ---------------------------------------------------------
        val afterRegister = writersOf(mandateId)
        assertThat(afterRegister)
            .describedAs("exactly one sdd_outbox row for mandate %s after registration", mandateId)
            .hasSize(1)
        val registered = afterRegister.single()
        assertThat(registered.eventType).isEqualTo(EVENT_REGISTERED)
        assertThat(registered.outboxXmin)
            .describedAs(
                "the sdd_mandate row and its outbox row must carry the SAME Postgres xmin — " +
                    "different values mean two transactions wrote them, so one can commit without " +
                    "the other (mandate xmin=%s, outbox xmin=%s)",
                registered.mandateXmin,
                registered.outboxXmin,
            )
            .isEqualTo(registered.mandateXmin)

        // --- write path 2: the lifecycle transition helper ------------------------------------
        suspendMandate(mandateId)
        val afterSuspend = writersOf(mandateId)
        assertThat(afterSuspend).hasSize(2)
        val suspended = afterSuspend.single { it.eventType == EVENT_SUSPENDED }
        assertThat(suspended.outboxXmin)
            .describedAs(
                "suspend must also write the mandate row and its outbox row in one transaction " +
                    "(mandate xmin=%s, outbox xmin=%s)",
                suspended.mandateXmin,
                suspended.outboxXmin,
            )
            .isEqualTo(suspended.mandateXmin)

        // Known-different control, so one run shows the identical comparison both matching and NOT
        // matching. The suspend UPDATEd the mandate row, giving it a new version whose xmin is the
        // suspending transaction's; the REGISTRATION outbox row was written by an earlier
        // transaction and therefore must NOT match the current mandate row. Were the comparison
        // matching everything, this would fail.
        val staleRegistration = afterSuspend.single { it.eventType == EVENT_REGISTERED }
        assertThat(staleRegistration.outboxXmin)
            .describedAs(
                "control: the registration outbox row was written by an earlier transaction than " +
                    "the current mandate row version (registration outbox xmin=%s, mandate xmin=%s)",
                staleRegistration.outboxXmin,
                staleRegistration.mandateXmin,
            )
            .isNotEqualTo(staleRegistration.mandateXmin)
    }

    /**
     * Guards the assertions above against reading their own success from an empty set: a mandate id
     * that was never written must produce no pair at all, so `hasSize(1)` is a claim the query is
     * capable of failing.
     */
    @Test
    fun `the atomicity query returns nothing for a mandate that was never written`() {
        assertThat(writersOf(UUID.randomUUID())).isEmpty()
    }

    private data class WriterPair(val mandateXmin: String, val outboxXmin: String, val eventType: String)

    /** The transaction ids (`xmin`) that wrote the aggregate row and each of its outbox rows. */
    private fun writersOf(mandateId: UUID): List<WriterPair> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT m.xmin::text AS mandate_xmin, o.xmin::text AS outbox_xmin, o.event_type
            FROM sdd_mandate m
            JOIN sdd_outbox o ON o.aggregate_id = m.id
            WHERE m.id = ?
            ORDER BY o.id
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, mandateId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }
                    .map { WriterPair(it.getString(1), it.getString(2), it.getString(3)) }
                    .toList()
            }
        }
    }

    private fun registerCoreMandate(): UUID {
        val registered = Given {
            contentType("application/json")
            body(
                """
                {
                  "accountId": "${UUID.randomUUID()}",
                  "debtorIban": "CZ6508000000192000145399",
                  "creditorIdentifier": "CZ98ZZZ${UUID.randomUUID().toString().take(8)}",
                  "umr": "UMR-${UUID.randomUUID().toString().take(UMR_SUFFIX_LENGTH)}",
                  "scheme": "CORE",
                  "sequenceType": "FRST",
                  "creditorName": "Energie a.s.",
                  "debtorName": "Jan Novak",
                  "signatureDate": "2026-01-15"
                }
                """.trimIndent(),
            )
        } When {
            post("/api/v1/sdd/mandates")
        } Then {
            statusCode(201)
        }
        val body = registered.extract()
        // Arrangement assertion: only a CORE mandate is born ACTIVE, and only an ACTIVE mandate can
        // then be suspended. A change that routed this to PENDING_CONFIRMATION would otherwise
        // leave the second half of the test asserting over a failed request.
        assertThat(body.path<String>("status")).isEqualTo("ACTIVE")
        return UUID.fromString(body.path("id"))
    }

    private fun suspendMandate(mandateId: UUID) {
        val suspended = Given {
            contentType("application/json")
        } When {
            post("/api/v1/sdd/mandates/$mandateId/suspend")
        } Then {
            statusCode(200)
        }
        assertThat(suspended.extract().path<String>("status")).isEqualTo("SUSPENDED")
    }

    private companion object {
        const val ACTOR_ID = "00000000-0000-0000-0000-000000000099"

        /** `sdd_mandate.umr` is `varchar(35)`; a full UUID after the `UMR-` prefix overflows it. */
        const val UMR_SUFFIX_LENGTH = 20

        /** The wire values `SddMandateService` stamps on the outbox row — the subject. */
        const val EVENT_REGISTERED = "sdd.mandate.registered.v1"
        const val EVENT_SUSPENDED = "sdd.mandate.suspended.v1"
    }
}
