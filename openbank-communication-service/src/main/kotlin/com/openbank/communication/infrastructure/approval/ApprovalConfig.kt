// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.communication.infrastructure.approval

import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.impl.RedisApprovalStore
import io.quarkus.redis.datasource.ReactiveRedisDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * Per-service producer for [ApprovalStore] (ADR-0155, wired for ADR-0285 D3's
 * `commstyle.publish`). Mirrors `com.openbank.notification.infrastructure.approval.ApprovalConfig`.
 *
 * Producing this bean makes `AuthorizeInterceptor.approvalStore.isResolvable` true, which
 * matters beyond this service: `isResolvable` checks CDI bean presence, not whether
 * [ReactiveRedisDataSource] can actually reach a Redis instance. `gitops/components/communication/
 * redis.yaml` and the `QUARKUS_REDIS_HOSTS` override on the Deployment are what make that true —
 * without them this bean would exist but every call would fail on Redis connection, not fail
 * open with the interceptor's documented log line (issue #1354, found live in lending-service's
 * identical wiring with no Redis behind it).
 */
@ApplicationScoped
class ApprovalConfig {
    @Produces
    @ApplicationScoped
    fun approvalStore(redis: ReactiveRedisDataSource, clock: Clock): ApprovalStore = RedisApprovalStore(redis, clock)
}
