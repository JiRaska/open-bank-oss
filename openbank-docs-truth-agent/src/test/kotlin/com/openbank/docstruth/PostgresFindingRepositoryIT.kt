// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth

import com.openbank.docstruth.domain.model.DocsTruthCheckType
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.FindingSeverity
import com.openbank.docstruth.domain.model.FindingStatus
import com.openbank.docstruth.infrastructure.persistence.PostgresFindingRepository
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
 * Repository round-trip against a real Testcontainers PostgreSQL (ADR-0166) — the thing unit tests
 * cannot cover: that Flyway's `findings` schema and reactive Panache actually persist and read back
 * a [DocsTruthFinding]. The entity↔domain mapping is hand-written per field, so a forgotten
 * assignment silently nulls a column rather than failing to compile; only a round-trip catches it.
 *
 * [PostgresFindingRepository] is reactive (`Mutiny.SessionFactory.withSession/withTransaction`
 * bridged to `suspend`), so its calls MUST run on a Vert.x duplicated context — a plain test thread
 * has none and fails with "No current Vertx context found". [onVertxContext] bridges the suspend
 * body via [VertxContextSupport.subscribeAndAwait] (mirrors anacredit-service's
 * `PostgresCreditExposureRepositoryIT`). Each test declares an explicit `: Unit` return — a
 * `fun x() = expr` inferring a non-`Unit` type is silently dropped by JUnit5/Kotlin.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PostgresFindingRepositoryIT {

    @Inject
    lateinit var repository: PostgresFindingRepository

    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    private fun finding(
        status: FindingStatus = FindingStatus.OPEN,
        title: String = "ADR-0139 claims Shipped but the artifact is absent",
    ) = DocsTruthFinding(
        id = Ids.newId().toString(),
        checkType = DocsTruthCheckType.SHIPPED_ARTIFACT_MISSING,
        severity = FindingSeverity.WARNING,
        detectedAt = Instant.parse("2026-07-16T10:00:00Z"),
        title = title,
        component = "ADR-0139",
        adrPath = "docs/adr/0139-ml-decisioning-platform.md",
        rawMetricValue = BigDecimal("1"),
        threshold = BigDecimal("0"),
        status = status,
    )

    @Test
    fun `a saved finding round-trips through every mapped field`(): Unit = onVertxContext {
        val saved = finding().copy(
            rootCause = "the ADR shipped before its artifact landed",
            proposalUrl = "https://github.com/JiRaska/open-bank-oss/pull/1",
            proposedFixDiff = "- Delivery-Status: Shipped\n+ Delivery-Status: Partial",
            diagnosedAt = Instant.parse("2026-07-16T11:00:00Z"),
            proposedAt = Instant.parse("2026-07-16T12:00:00Z"),
        )
        repository.save(saved)

        // Recursive comparison, not field-by-field asserts: a column added to the entity but
        // forgotten in one of the two mappers is exactly the defect this test exists for, and an
        // explicit assert list would have to be remembered too.
        assertThat(repository.findById(saved.id)).usingRecursiveComparison().isEqualTo(saved)
    }

    @Test
    fun `nullable fields round-trip as null rather than empty strings`(): Unit = onVertxContext {
        val bare = finding()
        repository.save(bare)

        val loaded = repository.findById(bare.id)
        assertThat(loaded?.rootCause).isNull()
        assertThat(loaded?.proposalUrl).isNull()
        assertThat(loaded?.proposedFixDiff).isNull()
        assertThat(loaded?.diagnosedAt).isNull()
        assertThat(loaded?.proposedAt).isNull()
    }

    @Test
    fun `update advances the HITL lifecycle in place`(): Unit = onVertxContext {
        val original = finding()
        repository.save(original)

        repository.update(
            original.copy(
                status = FindingStatus.DIAGNOSED,
                rootCause = "the ADR shipped before its artifact landed",
                diagnosedAt = Instant.parse("2026-07-16T11:00:00Z"),
            ),
        )

        val loaded = repository.findById(original.id)
        assertThat(loaded?.status).isEqualTo(FindingStatus.DIAGNOSED)
        assertThat(loaded?.rootCause).isEqualTo("the ADR shipped before its artifact landed")
    }

    @Test
    fun `update upserts a finding whose row was never written`(): Unit = onVertxContext {
        // The HITL path can hand back a finding minted before a restart, whose row never landed.
        // update() must persist it rather than throw — that is why it is an upsert, not a merge.
        val neverSaved = finding(title = "never persisted")
        repository.update(neverSaved)

        assertThat(repository.findById(neverSaved.id)?.title).isEqualTo("never persisted")
    }

    @Test
    fun `findActive excludes terminal statuses`(): Unit = onVertxContext {
        val open = finding(status = FindingStatus.OPEN, title = "open one")
        val resolved = finding(status = FindingStatus.RESOLVED, title = "resolved one")
        val rejected = finding(status = FindingStatus.REJECTED, title = "rejected one")
        listOf(open, resolved, rejected).forEach { repository.save(it) }

        val active = repository.findActive().map { it.id }

        assertThat(active).contains(open.id)
        // The point of the HITL queue: a decided finding must not resurface after a restart.
        assertThat(active).doesNotContain(resolved.id, rejected.id)
    }

    @Test
    fun `findById returns null for an unknown id rather than throwing`(): Unit = onVertxContext {
        assertThat(repository.findById(Ids.newId().toString())).isNull()
    }
}
