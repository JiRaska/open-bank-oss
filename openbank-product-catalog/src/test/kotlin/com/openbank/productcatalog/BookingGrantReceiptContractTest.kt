// SPDX-License-Identifier: Apache-2.0

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@TestProfile(BookingGrantReceiptContractTest.AccountCallerProfile::class)
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_booking_receipt")],
    restrictToAnnotatedClass = true,
)
class BookingGrantReceiptContractTest {
    @Inject
    lateinit var dataSource: DataSource

    @Test
    fun anonymousCallerIsRejected() {
        submit().statusCode(401)
    }

    @Test
    @TestSecurity(user = "same-client-human", roles = ["ROLE_ADMIN"])
    @OidcSecurity(claims = [Claim(key = "azp", value = CLIENT), Claim(key = "sub", value = "human-subject")])
    fun sameClientWithDifferentSubjectIsRejected() {
        submit().statusCode(403)
    }

    @Test
    @TestSecurity(user = "account-service", roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = CLIENT), Claim(key = "sub", value = SUBJECT)])
    fun accountServiceReceivesConflictForUnknownGrant() {
        submit().statusCode(409).body("code", equalTo("BOOKING_GRANT_UNAVAILABLE"))
    }

    @Test
    @TestSecurity(user = "account-service", roles = ["ROLE_API"])
    @OidcSecurity(claims = [Claim(key = "azp", value = CLIENT), Claim(key = "sub", value = SUBJECT)])
    fun accountCallerReplaysCommittedReceipt() {
        val specification = UUID.randomUUID()
        val offering = UUID.randomUUID()
        val product = UUID.randomUUID()
        val grant = UUID.randomUUID()
        val operation = UUID.randomUUID()
        val party = UUID.randomUUID()
        val schema = "org.openbank.market.booking-grant"
        val company = UUID.randomUUID()
        val book = UUID.randomUUID()
        val branch = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO catalog_schemas VALUES ('$schema:1', '$schema', 1, '{}', repeat('a',64), now())
                    ON CONFLICT (schema_id, schema_version) DO NOTHING
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO catalog_specifications (id, code, schema_id, schema_version, created_at)
                    VALUES ('$specification', '$specification', '$schema', 1, now())
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO catalog_offerings (id, specification_id, code, market)
                    VALUES ('$offering', '$specification', '$offering', '{}')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO catalog_revisions
                      (id, offering_id, revision_no, schema_id, schema_version, state, content, effective_from,
                       maker_id, checker_id, approval_digest, created_at, updated_at)
                    VALUES
                      ('$product', '$offering', 1, '$schema', 1, 'PUBLISHED', '{}', now() - interval '1 day',
                       'maker', 'checker', repeat('a',64), now(), now()),
                      ('$grant', '$offering', 2, '$schema', 1, 'PUBLISHED',
                       jsonb_build_object('attributes', jsonb_build_object(
                           'productRevisionId', '$product', 'mode', 'BRANCH', 'homeCountry', 'CZ',
                           'hostCountry', 'SK', 'legalEntityId', '$company', 'bookingBookId', '$book',
                           'establishmentId', '$branch', 'approvalEvidenceHash', repeat('a',64))),
                       now() - interval '1 day', 'maker', 'checker', repeat('b',64), now(), now())
                    """.trimIndent(),
                )
            }
        }
        val committedAt = submit(operation, party, product, grant).statusCode(200)
            .body("bookedContext.establishmentId", equalTo(branch.toString()))
            .extract().path<String>("consumedAt")
        submit(operation, party, product, grant).statusCode(200).body("consumedAt", equalTo(committedAt))
        submit(operation, UUID.randomUUID(), product, grant).statusCode(409)
            .body("code", equalTo("BOOKING_OPERATION_CONFLICT"))
    }

    private fun submit(
        operation: UUID = UUID.randomUUID(),
        party: UUID = UUID.randomUUID(),
        product: UUID = UUID.randomUUID(),
        grant: UUID = UUID.randomUUID(),
    ) = given().contentType("application/json")
        .body(
            mapOf(
                "operationId" to operation.toString(),
                "subjectId" to party.toString(),
                "productRevisionId" to product.toString(),
                "grantRevisionId" to grant.toString(),
            ),
        )
        .post("/api/v2/booking-grant-receipts").then()

    class AccountCallerProfile : QuarkusTestProfile {
        override fun getConfigOverrides() = mapOf(
            "openbank.catalog.booking.account-client" to CLIENT,
            "openbank.catalog.booking.account-subject" to SUBJECT,
        )
    }

    private companion object {
        const val CLIENT = "test-account-client"
        const val SUBJECT = "test-account-subject"
    }
}
