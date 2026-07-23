// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.infrastructure.persistence.PostgresAnomalyRepository
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Repository round-trip against a real Testcontainers PostgreSQL (ADR-0112 / ADR-0148) — the thing
 * unit tests cannot cover: that Flyway's `anomalies` schema and reactive Panache actually persist
 * and read back a [CostAnomaly]. The entity↔domain mapping is hand-written per field, so a forgotten
 * assignment silently nulls a column rather than failing to compile; only a round-trip catches it.
 *
 * [PostgresAnomalyRepository] is reactive (`Mutiny.SessionFactory.withSession/withTransaction`
 * bridged to `suspend`), so its calls MUST run on a Vert.x duplicated context — a plain test thread
 * has none and fails with "No current Vertx context found". [onVertxContext] bridges the suspend
 * body via [VertxContextSupport.subscribeAndAwait] (mirrors docs-truth-agent's
 * `PostgresFindingRepositoryIT`). Each test declares an explicit `: Unit` return — a
 * `fun x() = expr` inferring a non-`Unit` type is silently dropped by JUnit5/Kotlin.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresAnomalyRepositoryIT {

    @Inject
    lateinit var repository: PostgresAnomalyRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun anomaly(
        status: AnomalyStatus = AnomalyStatus.OPEN,
        title: String = "NAT egress spike on the shared gateway",
    ) = CostAnomaly(
        id = Ids.newId().toString(),
        detector = DetectorId.D1_NAT_EGRESS,
        severity = AnomalySeverity.WARNING,
        detectedAt = Instant.parse("2026-07-23T03:00:00Z"),
        title = title,
        rawMetricValue = BigDecimal("128.50"),
        threshold = BigDecimal("50.00"),
        affectedResource = "nat-gateway/eu-north-1a",
        status = status,
    )

    @Test
    fun `a saved anomaly round-trips through every mapped field`(): Unit = onVertxContext {
        val saved = anomaly().copy(
            rootCause = "a chatty S3 sync job routed over the NAT instead of the gateway endpoint",
            proposalPrUrl = "https://github.com/JiRaska/open-bank-oss/pull/1",
            proposedIacDiff = "+ resource \"aws_vpc_endpoint\" \"s3\" { ... }",
            estimatedMonthlySavingUsd = BigDecimal("74.00"),
            diagnosedAt = Instant.parse("2026-07-23T03:05:00Z"),
            proposedAt = Instant.parse("2026-07-23T03:10:00Z"),
        )
        repository.save(saved)

        // Recursive comparison, not field-by-field asserts: a column added to the entity but
        // forgotten in one of the two mappers is exactly the defect this test exists for.
        assertThat(repository.findById(saved.id)).usingRecursiveComparison().isEqualTo(saved)
    }

    @Test
    fun `nullable fields round-trip as null rather than empty strings`(): Unit = onVertxContext {
        val bare = anomaly()
        repository.save(bare)

        val loaded = repository.findById(bare.id)
        assertThat(loaded?.rootCause).isNull()
        assertThat(loaded?.proposalPrUrl).isNull()
        assertThat(loaded?.proposedIacDiff).isNull()
        assertThat(loaded?.estimatedMonthlySavingUsd).isNull()
        assertThat(loaded?.diagnosedAt).isNull()
        assertThat(loaded?.proposedAt).isNull()
    }

    @Test
    fun `update advances the HITL lifecycle in place`(): Unit = onVertxContext {
        val original = anomaly()
        repository.save(original)

        repository.update(
            original.copy(
                status = AnomalyStatus.DIAGNOSED,
                rootCause = "chatty S3 sync over NAT",
                diagnosedAt = Instant.parse("2026-07-23T03:05:00Z"),
            ),
        )

        val loaded = repository.findById(original.id)
        assertThat(loaded?.status).isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(loaded?.rootCause).isEqualTo("chatty S3 sync over NAT")
    }

    @Test
    fun `update upserts an anomaly whose row was never written`(): Unit = onVertxContext {
        // The HITL path can hand back an anomaly minted before a restart, whose row never landed.
        // update() must persist it rather than throw — that is why it is an upsert, not a merge.
        val neverSaved = anomaly(title = "never persisted")
        repository.update(neverSaved)

        assertThat(repository.findById(neverSaved.id)?.title).isEqualTo("never persisted")
    }

    @Test
    fun `findActive excludes terminal statuses`(): Unit = onVertxContext {
        val open = anomaly(status = AnomalyStatus.OPEN, title = "open one")
        val resolved = anomaly(status = AnomalyStatus.RESOLVED, title = "resolved one")
        val rejected = anomaly(status = AnomalyStatus.REJECTED, title = "rejected one")
        listOf(open, resolved, rejected).forEach { repository.save(it) }

        val active = repository.findActive().map { it.id }

        assertThat(active).contains(open.id)
        // The point of the cross-restart memory: a decided anomaly must not resurface on the next
        // 03:00 run and get re-proposed.
        assertThat(active).doesNotContain(resolved.id, rejected.id)
    }

    @Test
    fun `findById returns null for an unknown id rather than throwing`(): Unit = onVertxContext {
        assertThat(repository.findById(Ids.newId().toString())).isNull()
    }
}
