// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.shadow

import com.openbank.interest.application.port.out.CatalogRevisionIrPort
import com.openbank.interest.application.port.out.CatalogRevisionUnavailableException
import com.openbank.interest.application.port.out.ProductShadowQueuePort
import com.openbank.interest.application.port.out.QueuedShadowInput
import com.openbank.interest.application.port.out.RevisionIrCopyPort
import com.openbank.interest.application.port.out.ShadowCompletion
import com.openbank.interest.application.port.out.ShadowLegacySourcePort
import com.openbank.interest.application.port.out.StoredRevisionIr
import com.openbank.interest.application.usecase.InterestShadowComparator
import com.openbank.interest.application.usecase.ProductShadowService
import com.openbank.interest.application.usecase.ProductShadowSources
import com.openbank.interest.domain.shadow.ShadowLegacyDecision
import com.openbank.interest.domain.shadow.ShadowSourceKind
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.productrevision.IrSignatureVerifierPort
import com.openbank.libs.productrevision.shadow.ProductShadowRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The interest shadow end to end over in-memory ports (ADR-0352 §6): what it COUNTS is the contract the
 * ProductShadowDiverged / ProductShadowComparedNothing / ProductShadowStalled rules read, so each test
 * asserts the series, not only the stored outcome. Negative cases first.
 */
class ProductShadowServiceTest {

    private val registry = SimpleMeterRegistry()

    private class World(vararg decisions: ShadowLegacyDecision) :
        ProductShadowQueuePort,
        ShadowLegacySourcePort {
        val rows = decisions.associateBy { it.sourceId }
        val pending = ArrayList<QueuedShadowInput>()
        val completed = LinkedHashMap<UUID, ShadowCompletion>()
        val retried = LinkedHashMap<UUID, String>()
        private val ids = LinkedHashMap<UUID, UUID>()

        override suspend fun intake(lookbackDays: Long, limit: Int): Int {
            val fresh = rows.keys.filter { it !in ids.values }.take(limit)
            fresh.forEach { src ->
                val id = UUID.randomUUID()
                ids[id] = src
                pending += QueuedShadowInput(id, ShadowSourceKind.ACCRUAL, src, 0)
            }
            return fresh.size
        }
        override suspend fun claim(limit: Int) = pending.take(limit).also { pending.removeAll(it) }
        override suspend fun complete(id: UUID, completion: ShadowCompletion) {
            completed[id] = completion
        }
        override suspend fun retryLater(id: UUID, outcome: String) {
            retried[id] = outcome
        }
        override suspend fun load(kind: ShadowSourceKind, sourceId: UUID) = rows[sourceId]
    }

    private class Copies : RevisionIrCopyPort {
        val rows = mutableMapOf<String, StoredRevisionIr>()
        override suspend fun find(revisionId: String) = rows[revisionId]
        override suspend fun store(copy: StoredRevisionIr) = rows.getOrPut(copy.revisionId) { copy }
    }

    private fun signed(revisionId: String, ir: String = ShadowFixtures.SAVINGS_CZK): StoredRevisionIr {
        val identity = ShadowFixtures.identity(revisionId, ir)
        return StoredRevisionIr(
            revisionId,
            true,
            identity.revisionHash,
            ir,
            ShadowFixtures.signer.sign(identity),
            identity = identity,
            contentHash = ShadowFixtures.CONTENT_HASH,
        )
    }

    private fun service(
        world: World,
        fetch: (String) -> StoredRevisionIr,
        verifier: IrSignatureVerifierPort? = ShadowFixtures.signer,
    ) = ProductShadowService(
        ProductShadowSources(
            world,
            world,
            Copies(),
            object : CatalogRevisionIrPort {
                override suspend fun fetch(revisionId: String) = fetch(revisionId)
            },
        ),
        verifier,
        InterestShadowComparator(ProductInterpreter(FinanceProductArithmetic)),
        ProductShadowRecorder(registry, "interest"),
        registry,
    )

    private fun count(name: String, vararg tags: String) = registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun drain(svc: ProductShadowService) = runBlocking {
        svc.intake(lookbackDays = 35, limit = 100)
        svc.runOnce(100)
    }

    @Test
    fun `a revision with a different rate is recorded as DIVERGED with both amounts`() {
        val world = World(ShadowFixtures.accrual("250000.00", "20.547950"))
        drain(service(world, { signed(it, ShadowFixtures.withRate("0.0310000000")) }))

        val done = world.completed.values.single()
        assertThat(done.outcome).isEqualTo("DIVERGED")
        assertThat(done.ruleId).isEqualTo("interest.base")
        assertThat(done.legacyAmount).isEqualByComparingTo("20.547950")
        assertThat(done.irAmount).isEqualByComparingTo("21.232875")
        assertThat(
            count(ProductShadowRecorder.DIVERGED, "consumer", "interest", "rule_id", "interest.base"),
        ).isEqualTo(1.0)
        assertThat(
            count(ProductShadowRecorder.COMPARED, "consumer", "interest", "product", "SAVINGS-CZK"),
        ).isEqualTo(1.0)
    }

    @Test
    fun `queued inputs that compare nothing produce exactly the ComparedNothing series - inputs, no compared`() {
        // Verification switched off: three IR-bearing inputs reach the comparator and none is compared.
        val world = World(
            ShadowFixtures.accrual("1000.00", "0.082192"),
            ShadowFixtures.accrual("2000.00", "0.164384"),
            ShadowFixtures.accrual("3000.00", "0.246575"),
        )
        drain(service(world, { signed(it) }, verifier = null))

        assertThat(count(ProductShadowRecorder.INPUTS, "consumer", "interest")).isEqualTo(3.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counter()).isNull()
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "verification_disabled")).isEqualTo(3.0)
        assertThat(world.completed.values.map { it.outcome }).containsOnly("VERIFICATION_DISABLED")
    }

    @Test
    fun `no events leaves every shadow series absent - never agreement it did not measure`() {
        val world = World()
        assertThat(drain(service(world, { signed(it) }))).isZero()
        assertThat(registry.meters).isEmpty()
    }

    @Test
    fun `an unpinned account, a revision without IR and a bad signature are each counted, never compared`() {
        val unpinned = ShadowFixtures.accrual("1000.00", "0.082192", revisionId = null)
        val noIr = ShadowFixtures.accrual("1000.00", "0.082192")
        val forged = ShadowFixtures.accrual("1000.00", "0.082192")
        val world = World(unpinned, noIr, forged)
        drain(
            service(world, { id ->
                when (id) {
                    noIr.productRevisionId -> StoredRevisionIr(id, false, null, null, null)
                    else -> signed(id).let { it.copy(signature = it.signature!!.copy(signature = "AAAA")) }
                }
            }),
        )
        assertThat(world.completed.values.map { it.outcome })
            .containsExactlyInAnyOrder("NO_PIN", "NO_IR", "VERIFICATION_FAILED")
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "no_ir")).isEqualTo(2.0)
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "verification_failed")).isEqualTo(1.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counter()).isNull()
    }

    @Test
    fun `a copy that is not the account's pinned revision is never compared`() {
        // Same revision id, valid signature over its own identity, but the catalog content hash differs from
        // the one interest recorded when it pinned the account: another revision's terms, refused.
        val otherContent = ShadowFixtures.accrual("250000.00", "20.547950")
        // A copy whose envelope names another revision id, validly signed for THAT revision.
        val otherRevision = ShadowFixtures.accrual("250000.00", "20.547950")
        val world = World(otherContent, otherRevision)
        drain(
            service(world, { id ->
                when (id) {
                    otherContent.productRevisionId -> signed(id).copy(contentHash = "d".repeat(64))
                    else -> signed(UUID.randomUUID().toString()).copy(revisionId = id)
                }
            }),
        )
        assertThat(world.completed.values.map { it.outcome }).containsOnly("PIN_MISMATCH")
        assertThat(count(ProductShadowRecorder.NOT_COMPARABLE, "reason", "verification_failed")).isEqualTo(2.0)
        assertThat(registry.find(ProductShadowRecorder.COMPARED).counter()).isNull()
    }

    @Test
    fun `an unavailable catalog is retried, not completed`() {
        val world = World(ShadowFixtures.accrual("1000.00", "0.082192"))
        drain(service(world, { throw CatalogRevisionUnavailableException("down") }))
        assertThat(world.completed).isEmpty()
        assertThat(world.retried.values).containsExactly("REVISION_UNAVAILABLE")
    }

    @Test
    fun `the reference revision agrees with the legacy accrual`() {
        val world = World(ShadowFixtures.accrual("250000.00", "20.547950"))
        drain(service(world, { signed(it) }))
        assertThat(world.completed.values.single().outcome).isEqualTo("AGREED")
        assertThat(registry.find(ProductShadowRecorder.DIVERGED).counter()).isNull()
    }
}
