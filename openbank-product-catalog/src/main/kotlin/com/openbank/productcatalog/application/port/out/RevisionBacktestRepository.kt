// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application.port.out

import com.openbank.productcatalog.domain.catalog.RevisionBacktest
import java.util.UUID

/** ADR-0352 §7: the approval gate's evidence store — written once per valid backtest, never changed. */
interface RevisionBacktestRepository {
    /**
     * Records [backtest] only while the revision row is still at [expectedRowVersion] — the version the
     * replay read — checked under a row lock in the same transaction; otherwise
     * `CatalogPreconditionFailedException` (412) and nothing is written. An edit landing during the
     * replay therefore cannot leave evidence bound to content nobody backtested.
     */
    suspend fun record(backtest: RevisionBacktest, expectedRowVersion: Long)

    /** Backtests of [revisionId] at [digest]; the caller decides which principal must have run one. */
    suspend fun find(revisionId: UUID, digest: String): List<RevisionBacktest>
}
