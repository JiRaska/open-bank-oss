// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.domain.model

import com.openbank.libs.domain.event.EventActor

/**
 * The `actorId` this service stamps on its outbox events (#3994).
 *
 * Both `account.statement.period.closed.v1` (78 unattributed audit rows) and
 * `account.statement.period.close_failed.v1` (8) are produced by the period-close run. Nobody
 * presses a button: the close is driven by the scheduled orchestrator against the accounting
 * calendar, so `SYSTEM` is not a fallback here, it is the complete and correct answer.
 *
 * A named constant rather than a literal inside the two hand-built JSON payload strings — those
 * are `"""…"""` templates with no compiler check on their keys or values, so a divergent spelling
 * between the success and the failure event would silently create two origins in the audit trail.
 */
object StatementEventActors {
    /** The scheduled statement period-close run — success and failure alike. */
    val PERIOD_CLOSE: String = EventActor.system("statement-service", "period-close")
}
