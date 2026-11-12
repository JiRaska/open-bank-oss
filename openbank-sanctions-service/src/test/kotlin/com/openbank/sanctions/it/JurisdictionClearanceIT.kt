// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sanctions.it

import com.openbank.libs.testing.containers.PostgresTestResource
import com.openbank.sanctions.application.port.`in`.ReviewCommand
import com.openbank.sanctions.application.port.`in`.SanctionsUseCase
import com.openbank.sanctions.application.port.`in`.ScreenEntityCommand
import com.openbank.sanctions.domain.model.EntityType
import com.openbank.sanctions.domain.model.MatchKind
import com.openbank.sanctions.domain.model.SanctionsCheck
import com.openbank.sanctions.domain.model.SanctionsCheckStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * An analyst's clearance never covers a jurisdiction match (real Postgres, real use case).
 *
 * A clearance exists to dismiss a NAME false positive: "this subject is not the listed person".
 * A jurisdiction match is a fact about the country, not a possibly mistaken identity, so there is
 * nothing for a clearance to dismiss. When clearances did cover it, the match was identified by
 * list and country alone, and a clearance recorded while a country was HIGH_RISK went on to
 * whitelist the same country after a superseding publication made it an EMBARGO.
 *
 * Fixtures are synthetic: an invented name and the user-assigned ISO code `XJ`.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class JurisdictionClearanceIT {

    @Inject
    lateinit var useCase: SanctionsUseCase

    @Inject
    lateinit var dataSource: DataSource

    private fun <T> onEventLoop(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }

    private fun sql(statement: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(statement) } }
    }

    private fun jurisdiction(measure: String, version: Int) = sql(
        """
        INSERT INTO sanctions_jurisdictions
          (list_type, source_list, country_code, measure, version, source_ref, published_on, verified_on)
        VALUES ('FATF_HIGH_RISK', 'SYNTHETIC_CLEARANCE', '$COUNTRY', '$measure', $version,
                'synthetic clearance fixture', DATE '2026-01-01', DATE '2026-01-01')
        """.trimIndent(),
    )

    @BeforeEach
    fun seed() {
        cleanUp()
        sql(
            "INSERT INTO sanctions_entries (list_type, external_id, entity_type, primary_name, search_text) " +
                "VALUES ('OFAC_SDN', 'clearance-it-listed', 'INDIVIDUAL', '$LISTED', lower('$LISTED'))",
        )
        jurisdiction("HIGH_RISK", 1)
    }

    @AfterEach
    fun cleanUp() {
        sql("DELETE FROM sanctions_entries WHERE external_id = 'clearance-it-listed'")
        sql("DELETE FROM sanctions_jurisdictions WHERE source_list = 'SYNTHETIC_CLEARANCE'")
        sql("DELETE FROM sanctions_change_journal WHERE external_id = 'clearance-it-listed'")
    }

    private fun screen(name: String, subjectId: String, country: String? = COUNTRY): SanctionsCheck = onEventLoop {
        useCase.screen(
            ScreenEntityCommand(
                idempotencyKey = UUID.randomUUID().toString(),
                entityType = EntityType.INDIVIDUAL,
                name = name,
                subjectId = subjectId,
                profile = "PAYMENT",
                country = country,
            ),
        )
    }

    private fun whitelist(check: SanctionsCheck) = onEventLoop {
        useCase.review(ReviewCommand(check.id, "analyst-1", "reviewed", SanctionsCheckStatus.WHITELISTED))
    }

    @Test
    fun `control - a name clearance still whitelists the same subject when no country is involved`() {
        val subject = UUID.randomUUID().toString()
        val first = screen(LISTED, subject, country = null)
        assertThat(first.status).isEqualTo(SanctionsCheckStatus.HIT)
        whitelist(first)

        assertThat(screen(LISTED, subject, country = null).status).isEqualTo(SanctionsCheckStatus.WHITELISTED)
    }

    @Test
    fun `a subject holding a valid name clearance is not whitelisted in a listed country`() {
        val subject = UUID.randomUUID().toString()
        val first = screen(LISTED, subject)
        assertThat(first.status).isEqualTo(SanctionsCheckStatus.HIT)
        whitelist(first)

        val again = screen(LISTED, subject)

        // The name is cleared; the country is not something a clearance can dismiss.
        assertThat(again.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        assertThat(again.overallScore).isEqualTo(REVIEW_SCORE)
        assertThat(again.matches.map { it.matchKind }).contains(MatchKind.JURISDICTION)
        assertThat(again.clearanceExpiresAt).isNull()
    }

    @Test
    fun `a clearance recorded under HIGH_RISK does not pass a superseding EMBARGO`() {
        val subject = UUID.randomUUID().toString()
        val first = screen(HARMLESS, subject)
        assertThat(first.status).isEqualTo(SanctionsCheckStatus.POTENTIAL_HIT)
        whitelist(first)

        sql("UPDATE sanctions_jurisdictions SET active = false WHERE source_list = 'SYNTHETIC_CLEARANCE'")
        jurisdiction("EMBARGO", 2)

        assertThat(screen(HARMLESS, subject).status).isEqualTo(SanctionsCheckStatus.HIT)
    }

    @Test
    fun `a jurisdiction match is identified by country, publication, measure and version`() {
        val match = screen(HARMLESS, UUID.randomUUID().toString()).matches.single()

        assertThat(match.matchedId).isEqualTo("country:$COUNTRY:SYNTHETIC_CLEARANCE:HIGH_RISK:v1")
        assertThat(match.matchedName).isEqualTo(COUNTRY)
    }

    private companion object {
        const val LISTED = "Quillon Vandermeerschaft"
        const val HARMLESS = "Harmless Nobodyson"
        const val COUNTRY = "XJ"
        const val REVIEW_SCORE = 0.65
    }
}
