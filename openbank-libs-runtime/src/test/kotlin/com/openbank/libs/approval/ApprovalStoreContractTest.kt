// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.approval

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The executable contract every [ApprovalStore] must satisfy (#3349).
 *
 * Segregation of duties on a four-eyes action is asserted in prose in a dozen threat models and
 * enforced in exactly one line of code per implementation. This class is what makes that assertion
 * checkable, and it exists as a CONTRACT rather than a test of one class because there is already
 * more than one implementation: `RedisApprovalStore` in production and [InMemoryApprovalStore] as
 * the interceptor tests' double. A rule stated twice drifts unless both statements answer to the
 * same test.
 *
 * Subclass it for any new implementation. If a `PanacheApprovalStore` ever lands — the entity
 * already exists in this module — it belongs here on day one; otherwise the threat models keep
 * saying "the guard" as though there were only one.
 *
 * Beyond segregation of duties the contract fixes: every state transition is atomic under
 * concurrency, a store sees only its own namespace's records even over a shared backend, the
 * request binding round-trips unchanged, and one maker's open approvals per action are bounded.
 */
abstract class ApprovalStoreContractTest {

    /**
     * A store over this test's backend. Two calls with different [namespace]s MUST share the same
     * backend, so the namespace cases test isolation rather than two unrelated stores.
     */
    protected abstract fun newStore(namespace: String = "svc-a", maxPendingPerMakerAction: Int = 20): ApprovalStore

    @Test
    fun `maker actor kind survives the approval lifecycle`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create(
            "sanctions.clear",
            resourceId = "check-1",
            makerId = "agent:reviewer",
            makerActorKind = MakerActorKind.AI_AGENT,
        )

        assertThat(store.find(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
        store.decide(pending.id, decidedBy = "operator-2", approve = true)
        assertThat(store.find(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
        store.markExecuted(pending.id)
        assertThat(store.find(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.AI_AGENT)
    }

    @Test
    fun `maker actor kind is unknown when creation did not supply provenance`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = null, makerId = "maker-1")
        assertThat(store.find(pending.id)?.makerActorKind).isEqualTo(MakerActorKind.UNKNOWN)
    }

    @Test
    fun `a maker deciding their own pending approval is refused`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        // SelfApprovalNotAllowedException SPECIFICALLY. InvalidApprovalStateException is a sibling
        // subclass of IllegalStateException, so asserting the supertype would stay green with the
        // self-approval guard deleted and a non-PENDING fixture.
        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-1", approve = true) }
        }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
            .hasMessageContaining("operator-1")
    }

    @Test
    fun `a refused self-approval leaves the approval PENDING and undecided`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        runCatching { store.decide(pending.id, decidedBy = "operator-1", approve = true) }

        // The guard must refuse BEFORE writing. A refactor that threw but still persisted an
        // APPROVED record would leave the maker's own X-Approval-Id retry able to consume it — a
        // refusal that grants the very thing it refused.
        val after = store.find(pending.id)
        assertThat(after?.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(after?.decidedBy).isNull()
    }

    @Test
    fun `a different checker decides the same approval successfully`(): Unit = runBlocking {
        // Control. Without it, a store whose find() always returned null would also be "red on
        // self-approval" — for the wrong reason, and it would stay red after the guard's removal.
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")

        val decided = store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThat(decided?.status).isEqualTo(ApprovalStatus.APPROVED)
        assertThat(decided?.decidedBy).isEqualTo("operator-2")
    }

    @Test
    fun `the self-approval guard is checked before the status guard`(): Unit = runBlocking {
        // Order is not arbitrary: if status were checked first, a maker re-deciding their own
        // settled approval would be told "wrong state" instead of "you may not decide your own
        // request" — and a reader of that error would conclude the SoD control had been consulted
        // when it had not.
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")
        store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-1", approve = true) }
        }
            .isInstanceOf(SelfApprovalNotAllowedException::class.java)
            .isNotInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `an already-decided approval cannot be re-decided by a third party`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("sanctions.clear", resourceId = "check-1", makerId = "operator-1")
        store.decide(pending.id, decidedBy = "operator-2", approve = true)

        assertThatThrownBy {
            runBlocking { store.decide(pending.id, decidedBy = "operator-3", approve = true) }
        }.isInstanceOf(InvalidApprovalStateException::class.java)
    }

    @Test
    fun `five concurrent markExecuted calls consume an approval exactly once`(): Unit = runBlocking {
        val store = newStore()
        val pending = store.create("interest.create", null, "maker-1")
        store.decide(pending.id, "checker-1", approve = true)

        val results = (1..CONCURRENT_CALLS).map {
            async(Dispatchers.IO) { runCatching { store.markExecuted(pending.id) } }
        }.awaitAll()

        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.mapNotNull { it.exceptionOrNull() }).allMatch { it is InvalidApprovalStateException }
        assertThat(store.find(pending.id)?.status).isEqualTo(ApprovalStatus.EXECUTED)
    }

    @Test
    fun `an approve racing a reject has exactly one winner and the loser gets a conflict`(): Unit = runBlocking {
        val store = newStore()
        repeat(RACE_ROUNDS) { round ->
            val pending = store.create("interest.create", null, "maker-$round")
            val results = listOf(true, false).map { approve ->
                async(Dispatchers.IO) {
                    runCatching { store.decide(pending.id, if (approve) "checker-a" else "checker-r", approve) }
                }
            }.awaitAll()

            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(results.single { it.isFailure }.exceptionOrNull())
                .isInstanceOf(InvalidApprovalStateException::class.java)
            val winner = results.single { it.isSuccess }.getOrThrow()
            assertThat(store.find(pending.id)?.status).isEqualTo(winner?.status)
        }
    }

    @Test
    fun `a store in another namespace cannot list, find or decide this namespace's approvals`(): Unit = runBlocking {
        val serviceA = newStore(namespace = "svc-a")
        val serviceB = newStore(namespace = "svc-b")
        val pending = serviceA.create("interest.create", null, "maker-1")

        assertThat(serviceB.findPending(100)).isEmpty()
        assertThat(serviceB.find(pending.id)).isNull()
        assertThat(serviceB.decide(pending.id, "checker-1", approve = true)).isNull()
        assertThat(serviceB.markExecuted(pending.id)).isNull()
        assertThat(serviceA.find(pending.id)?.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(serviceA.findPending(100).map { it.id }).containsExactly(pending.id)
    }

    @Test
    fun `the request binding round-trips unchanged`(): Unit = runBlocking {
        val store = newStore()
        val binding = ApprovalRequestBinding("f".repeat(64), "action=interest.create args={\"rate\":\"0.5|x\"}")
        val pending = store.create("interest.create", null, "maker-1", binding = binding)

        val found = store.find(pending.id)
        assertThat(found?.requestFingerprint).isEqualTo(binding.fingerprint)
        assertThat(found?.summary).isEqualTo(binding.summary)
        assertThat(store.create("interest.create", null, "maker-1").requestFingerprint).isNull()
    }

    @Test
    fun `one maker's pending approvals per action are bounded, and a decision frees a slot`(): Unit = runBlocking {
        val store = newStore(maxPendingPerMakerAction = 2)
        val first = store.create("interest.create", null, "maker-1")
        store.create("interest.create", null, "maker-1")

        assertThatThrownBy { runBlocking { store.create("interest.create", null, "maker-1") } }
            .isInstanceOf(ApprovalLimitExceededException::class.java)
            .hasMessageContaining("interest.create")
        // Other makers and other actions have their own allowance.
        store.create("interest.create", null, "maker-2")
        store.create("ledger.post", null, "maker-1")

        store.decide(first.id, "checker-1", approve = false)
        store.create("interest.create", null, "maker-1")
    }

    private companion object {
        const val CONCURRENT_CALLS = 5
        const val RACE_ROUNDS = 20
    }
}

/** The production implementation's binding lives in `impl/RedisApprovalStoreIT`. */
class InMemoryApprovalStoreTest : ApprovalStoreContractTest() {
    private val backing = java.util.concurrent.ConcurrentHashMap<String, PendingApproval>()

    override fun newStore(namespace: String, maxPendingPerMakerAction: Int): ApprovalStore =
        InMemoryApprovalStore(namespace, backing, maxPendingPerMakerAction)
}
