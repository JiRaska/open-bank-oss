// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.libs.persistence.outbox.AbstractPanacheOutboxRepository
import com.openbank.libs.persistence.outbox.OutboxTableShape
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Outbox repository on the kernel base (ADR-0327 D1): claim by aggregate head with
 * `FOR UPDATE SKIP LOCKED` (#1201, D3), next_attempt_at backoff (D4), batched `markSent` (D6),
 * the O(1) count and retention all live in [AbstractPanacheOutboxRepository]. Rows are written by
 * `CaseActivitiesImpl`'s native INSERT inside the workflow activity's transaction, and
 * `CaseThreadReadRepository` reads them back by status — including SENT — for every case age.
 * Until proposal evidence has a separate durable source, this repository must not purge SENT.
 */
@ApplicationScoped
class CaseOutboxRepositoryImpl(clock: Clock) :
    AbstractPanacheOutboxRepository<CaseOutboxEntity>(
        OutboxTableShape("case_outbox"),
        CaseOutboxEntity::class.java,
        clock,
    ),
    PanacheRepository<CaseOutboxEntity> {
    // GET /cases/{caseId} projects proposal evidence directly from SENT case_outbox rows.
    // Do not purge them until that evidence has an independent durable source.
    override val sentRetentionExempt: Boolean = true
}
