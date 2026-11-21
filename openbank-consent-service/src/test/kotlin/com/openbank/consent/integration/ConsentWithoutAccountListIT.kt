// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.integration

import com.openbank.consent.it.ConsentPostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * A consent created WITHOUT an account list covers no account, through the real endpoints and a real
 * database: validate answers `CONSENT_ACCOUNT_NOT_COVERED` for any IBAN, whoever holds it. The list is
 * an element collection, so `null` is stored as no rows and read back as an empty list; the domain
 * agrees with that rather than treating "no list" as "every account".
 *
 * An explicit list is the control: its member is covered, so a denial above is the coverage rule
 * speaking, not a validate that denies everything.
 */
@QuarkusTest
@QuarkusTestResource(ConsentWithoutAccountListIT.InMemoryKafkaResource::class)
@QuarkusTestResource(ConsentPostgresRedisTestResource::class)
class ConsentWithoutAccountListIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> {
            val props = InMemoryConnector.switchOutgoingChannelsToInMemory("consent-events-out").toMutableMap()
            props["openbank.outbox.dispatch-enabled"] = "false"
            return props
        }

        override fun stop() = InMemoryConnector.clear()
    }

    @Inject
    lateinit var dataSource: DataSource

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a consent created without an account list covers neither an own nor a foreign IBAN`() {
        val id = activeConsent(accountIbans = null)

        assertThat(validate(id, OWN_IBAN)).isEqualTo(false to "CONSENT_ACCOUNT_NOT_COVERED")
        assertThat(validate(id, FOREIGN_IBAN)).isEqualTo(false to "CONSENT_ACCOUNT_NOT_COVERED")
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = ["ROLE_OPERATOR"])
    fun `a consent with an explicit account list covers exactly its members`() {
        val id = activeConsent(accountIbans = listOf(OWN_IBAN))

        assertThat(validate(id, OWN_IBAN)).isEqualTo(true to null)
        assertThat(validate(id, FOREIGN_IBAN)).isEqualTo(false to "CONSENT_ACCOUNT_NOT_COVERED")
    }

    private fun validate(id: UUID, iban: String): Pair<Boolean, String?> {
        val path = Given {
            contentType("application/json")
            body("""{"granteeId":"$GRANTEE","requiredScope":"ACCOUNTS_READ","accountIban":"$iban"}""")
        } When {
            post("/api/v1/consents/$id/validate")
        } Then {
            statusCode(200)
        } Extract {
            jsonPath()
        }
        return path.getBoolean("valid") to path.getString("code")
    }

    /** Created through the real endpoint, then made ACTIVE in the database (SCA is out of scope). */
    private fun activeConsent(accountIbans: List<String>?): UUID {
        val ibans = accountIbans?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"
        val id = Given {
            contentType("application/json")
            body(
                """
                {"partyId":"$PARTY","granteeId":"$GRANTEE","granteeType":"TPP","granteeName":"IT TPP",
                "scopes":["ACCOUNTS_READ"],"accountIbans":$ibans,
                "validTo":"${OffsetDateTime.now().plusDays(30)}","redirectUri":null,"tppTransactionId":null}
                """.trimIndent(),
            )
        } When {
            post("/api/v1/consents")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        val consentId = UUID.fromString(id)
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement("UPDATE consents SET status = 'ACTIVE' WHERE id = ?")
            ps.setObject(1, consentId)
            assertThat(ps.executeUpdate()).isEqualTo(1)
        }
        return consentId
    }

    private companion object {
        const val GRANTEE = "tpp-account-list-it"
        val PARTY: UUID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
        const val OWN_IBAN = "CZ6508000000192000145399"
        const val FOREIGN_IBAN = "CZ7108000000192000145399"
    }
}
