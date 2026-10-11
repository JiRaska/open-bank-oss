// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.domain.model

import java.util.UUID

/**
 * Which debtor accounts an authenticated initiator may pay FROM (ADR-0335 D5). Decided before
 * anything is persisted, so a refused instruction leaves no row and consumes no idempotency key.
 */
sealed interface InitiatorScope {
    fun permits(debtorAccountId: UUID): Boolean

    /** Staff and the customer edge (which carries its own ownership checks): unchanged behaviour. */
    data object General : InitiatorScope {
        override fun permits(debtorAccountId: UUID): Boolean = true
    }

    /**
     * A declared scoped initiator (rules.yaml `scoped_payment_initiators`): only from its one
     * configured debtor account. An unconfigured account (null) permits nothing — fail closed.
     */
    data class Scoped(val principal: String, val debtorAccountId: UUID?) : InitiatorScope {
        override fun permits(debtorAccountId: UUID): Boolean =
            this.debtorAccountId != null && this.debtorAccountId == debtorAccountId
    }

    /** A machine with no staff role and no declaration: may initiate nothing. */
    data object Undeclared : InitiatorScope {
        override fun permits(debtorAccountId: UUID): Boolean = false
    }
}
