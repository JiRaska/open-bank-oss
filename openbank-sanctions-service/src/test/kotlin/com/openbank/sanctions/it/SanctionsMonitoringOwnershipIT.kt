// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.infrastructure.persistence.repository.SanctionsEntryRepositoryImpl
import com.openbank.sanctions.it.MonitoringFixture.Companion.OTHER_OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.OWNER
import com.openbank.sanctions.it.MonitoringFixture.Companion.SHARED_CLIENT
import com.openbank.sanctions.it.MonitoringFixture.Companion.SUBJECT_PREFIX
import com.openbank.sanctions.it.MonitoringFixture.Companion.UNLISTED
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.response.Response
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * The owner of a monitored subject is the calling service, read from its token (ADR-0376).
 *
 * Over real HTTP and real Postgres. The other owner's row is planted by SQL, exactly as its own
 * registration would have written it, under the SAME subject reference the caller uses: the
 * strongest case, because nothing but the owner tells the two rows apart.
 *
 * Deregistration is used for the cross-owner case because it works with monitoring switched off
 * (the default here), so the test needs no settings override.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SanctionsMonitoringOwnershipIT {

    @Inject
    lateinit var entries: SanctionsEntryRepositoryImpl

    @Inject
    lateinit var dataSource: DataSource

    private lateinit var db: MonitoringFixture

    @BeforeEach
    fun setUp() {
        db = MonitoringFixture(dataSource, entries)
        db.cleanUp()
    }

    @AfterEach
    fun tearDown() = db.cleanUp()

    private fun owners(subjectRef: String): List<String> = db.query(
        "SELECT owner_service FROM sanctions_monitored_subjects WHERE subject_ref = '$subjectRef' ORDER BY 1",
    ) { it.getString(1) }

    private fun put(subjectRef: String): Response = RestAssured.given().contentType("application/json")
        .body("""{"entityType":"INDIVIDUAL","name":"$UNLISTED"}""")
        .put("/api/v3/sanctions/monitored-subjects/$subjectRef")

    private fun delete(subjectRef: String): Response =
        RestAssured.given().delete("/api/v3/sanctions/monitored-subjects/$subjectRef")

    @Test
    @AsKycService
    fun `a caller deletes its own subject and never another caller's under the same reference`() {
        val subject = "${SUBJECT_PREFIX}shared-ref"
        db.plant(OWNER, subject)
        db.plant(OTHER_OWNER, subject)

        assertThat(delete(subject).statusCode).isEqualTo(204)

        assertThat(owners(subject)).describedAs("only the caller's own row is gone").containsExactly(OTHER_OWNER)
        assertThat(db.count("sanctions_monitored_subject_names")).describedAs("and only its names").isEqualTo(1)
    }

    @Test
    @AsKycService
    fun `deleting a reference only another caller registered removes nothing`() {
        val subject = "${SUBJECT_PREFIX}not-mine"
        db.plant(OTHER_OWNER, subject)

        // 204 as for any absent row: the answer does not reveal that another owner holds the reference.
        assertThat(delete(subject).statusCode).isEqualTo(204)

        assertThat(owners(subject)).containsExactly(OTHER_OWNER)
    }

    @Test
    @AsSharedFleetClient
    fun `the shared fleet client is refused, because it names no single owner`() {
        val subject = "${SUBJECT_PREFIX}shared-client"
        db.plant(SHARED_CLIENT, subject)

        assertThat(put(subject).statusCode).isEqualTo(403)
        assertThat(delete(subject).statusCode).isEqualTo(403)

        assertThat(owners(subject)).describedAs("nothing was deleted").containsExactly(SHARED_CLIENT)
    }

    @Test
    @TestSecurity(user = "service-account-$OWNER", roles = ["ROLE_API"])
    fun `a principal that is not a verified service-account token is refused whatever its name says`() {
        val subject = "${SUBJECT_PREFIX}no-token"
        db.plant(OWNER, subject)

        assertThat(put(subject).statusCode).isEqualTo(403)
        assertThat(delete(subject).statusCode).isEqualTo(403)

        assertThat(owners(subject)).containsExactly(OWNER)
    }

    @Test
    @TestSecurity(user = "analyst", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
    fun `a staff role does not reach the operations`() {
        assertThat(put("${SUBJECT_PREFIX}staff").statusCode).isEqualTo(403)
        assertThat(delete("${SUBJECT_PREFIX}staff").statusCode).isEqualTo(403)
    }

    @Test
    fun `an unauthenticated caller is refused`() {
        assertThat(put("${SUBJECT_PREFIX}anonymous").statusCode).isEqualTo(401)
        assertThat(delete("${SUBJECT_PREFIX}anonymous").statusCode).isEqualTo(401)
    }
}
