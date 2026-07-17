// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.notification.integration

import com.openbank.libs.approval.ApprovalStore
import com.openbank.notification.it.RedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The regression test for issue #1354: `ApprovalConfig.approvalStore` being a resolvable CDI
 * bean is NOT proof that four-eyes actually works — `AuthorizeInterceptor` only checks
 * `Instance<ApprovalStore>.isResolvable` (bean presence), never whether the
 * `ReactiveRedisDataSource` behind it can reach anything. `lending-service` ships the identical
 * `ApprovalConfig` with no Redis deployed in gitops at all; this test is what that service is
 * missing — a real round-trip against a real Redis, so a broken `QUARKUS_REDIS_HOSTS` fails
 * this IT instead of failing silently the first time `AUTHZ_FOUR_EYES_ENFORCE` is flipped true
 * in production.
 */
@QuarkusTest
@QuarkusTestResource(RedisTestResource::class)
class ApprovalStoreWiringIT {

    @Inject
    lateinit var approvalStore: ApprovalStore

    // RedisApprovalStore's ReactiveRedisDataSource calls need a Vert.x duplicated context, same
    // constraint as reactive Panache — see OperatorMessageServiceIT.onVertxContext for the why
    // and the bridge shape (Dispatchers.Unconfined + future{} -> Uni.createFrom().completionStage).
    private fun <T> onVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    @Test
    fun `the produced ApprovalStore bean round-trips a real approval through real Redis`() {
        val pending = onVertxContext {
            approvalStore.create(action = "opsmessage.compose", resourceId = null, makerId = "operator-1")
        }
        assertThat(pending.action).isEqualTo("opsmessage.compose")
        assertThat(pending.makerId).isEqualTo("operator-1")

        val found = onVertxContext { approvalStore.find(pending.id) }
        assertThat(found).isNotNull
        assertThat(found?.id).isEqualTo(pending.id)

        val decided = onVertxContext { approvalStore.decide(pending.id, decidedBy = "operator-2", approve = true) }
        assertThat(decided?.decidedBy).isEqualTo("operator-2")
    }
}
