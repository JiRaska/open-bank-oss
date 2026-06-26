// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.ledger.application.port.out

import com.openbank.libs.persistence.outbox.OutboxRepository

/**
 * Outbound port for draining the ledger transactional outbox (ADR-0049 D3 / ADR-0050).
 *
 * Extends [OutboxRepository] (from openbank-libs) so the shared [com.openbank.libs.persistence.outbox.AbstractOutboxDispatcher]
 * dispatch loop can operate on the ledger outbox without a service-specific adapter.
 * [listProcessable], [countProcessable], [markSent] and [markFailed] are all inherited from
 * [OutboxRepository]; only the write-side entry point ([persistInTransaction]) is service-specific.
 */
interface LedgerOutboxRepository : OutboxRepository
