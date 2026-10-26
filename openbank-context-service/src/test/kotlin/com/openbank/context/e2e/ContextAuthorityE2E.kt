// SPDX-License-Identifier: Apache-2.0
package com.openbank.context.e2e

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * E2E proof of the delegation-history chain over the real wire: lifecycle events travel over
 * Redpanda as plain payloads consumed by `delegation-history-in`, are stored append-only with a
 * (delegation, revision) conflict rule, and are served by the authorization-gated history lens
 * with a `knownAt` knowledge cutoff. The in-memory-connector ITs seed the repository directly;
 * here the consumer itself — decoder, conflict check, meter outcomes — is what runs.
 */
@QuarkusTest
@TestProfile(ContextE2EProfile::class)
class ContextAuthorityE2E : ContextE2EBase() {

    @Test
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `an activated delegation over the real broker is readable with the review assignment`() {
        val delegation = UUID.randomUUID()
        val grantor = UUID.randomUUID()
        val grantee = UUID.randomUUID()
        publishDelegationEvent(delegationActivated(delegation, revision = 1, grantor = grantor, grantee = grantee))
        assertThat(awaitAuthorityRows(delegation, 1)).isEqualTo(1)
        val reviewCase = "review-${UUID.randomUUID()}"
        seedAssignment(
            INVESTIGATOR,
            caseId = reviewCase,
            purpose = AUTHORITY_PURPOSE,
            rootRef = "delegation:$delegation",
        )

        Given {
            header("X-Investigation-Case-Id", reviewCase)
            header("X-Investigation-Purpose", AUTHORITY_PURPOSE)
        } When { get("/api/v1/context/authorizations/$delegation") } Then {
            statusCode(200)
            body("root", equalTo("delegation:$delegation"))
            body("observations", hasSize<Any>(1))
            body("observations[0].evidence.eventType", equalTo("DelegationActivated"))
            body("observations[0].evidence.delegationId", equalTo(delegation.toString()))
            body("observations[0].evidence.grantorPartyId", equalTo(grantor.toString()))
            body("observations[0].evidence.granteePartyId", equalTo(grantee.toString()))
            body("observations[0].evidenceRef", equalTo("delegation:$delegation:1"))
        }
    }

    @Test
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `identical replay keeps a single observation under the revision conflict rule`() {
        val delegation = UUID.randomUUID()
        val payload = delegationActivated(delegation, revision = 1)
        publishDelegationEvent(payload)
        awaitAuthorityRows(delegation, 1)
        publishDelegationEvent(payload)

        Thread.sleep(1500)
        assertThat(
            jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM context_authority_history WHERE delegation_id = ?",
                ).use { ps ->
                    ps.setObject(1, delegation)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            },
        ).isEqualTo(1)
    }

    @Test
    fun `non lifecycle spend events are ignored without a history row or a dead letter`() {
        val delegation = UUID.randomUUID()
        val spend = delegationActivated(delegation, revision = 1, eventType = "SpendReserved")
        publishDelegationEvent(spend)

        Thread.sleep(1500)
        assertThat(
            jdbc { c ->
                c.prepareStatement(
                    "SELECT count(*) FROM context_authority_history WHERE delegation_id = ?",
                ).use { ps ->
                    ps.setObject(1, delegation)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            },
        ).isZero()
    }

    @Test
    @TestSecurity(user = INVESTIGATOR, roles = ["ROLE_COMPLIANCE"])
    fun `a knowledge cutoff hides observations recorded after it`() {
        val delegation = UUID.randomUUID()
        publishDelegationEvent(delegationActivated(delegation, revision = 1))
        awaitAuthorityRows(delegation, 1)
        val recordedAt = jdbc { c ->
            c.prepareStatement(
                "SELECT recorded_at FROM context_authority_history WHERE delegation_id = ? AND revision = 1",
            ).use { ps ->
                ps.setObject(1, delegation)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getTimestamp(1).toInstant()
                }
            }
        }
        publishDelegationEvent(
            delegationActivated(delegation, revision = 2, eventType = "DelegationRevoked"),
        )
        awaitAuthorityRows(delegation, 2)
        val reviewCase = "review-${UUID.randomUUID()}"
        seedAssignment(
            INVESTIGATOR,
            caseId = reviewCase,
            purpose = AUTHORITY_PURPOSE,
            rootRef = "delegation:$delegation",
        )

        // knownAt pinned after revision 1 was recorded: the later revocation is not yet known.
        Given {
            header("X-Investigation-Case-Id", reviewCase)
            header("X-Investigation-Purpose", AUTHORITY_PURPOSE)
            queryParam("knownAt", recordedAt.plusMillis(1).toString())
        } When { get("/api/v1/context/authorizations/$delegation") } Then {
            statusCode(200)
            body("observations", hasSize<Any>(1))
            body("observations[0].evidence.eventType", equalTo("DelegationActivated"))
        }

        // No cutoff: both revisions are visible, newest first.
        Given {
            header("X-Investigation-Case-Id", reviewCase)
            header("X-Investigation-Purpose", AUTHORITY_PURPOSE)
        } When { get("/api/v1/context/authorizations/$delegation") } Then {
            statusCode(200)
            body("observations", hasSize<Any>(2))
            body("observations[0].evidence.eventType", equalTo("DelegationRevoked"))
        }
    }
}
