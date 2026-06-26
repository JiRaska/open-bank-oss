// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.simulation.model

import java.util.UUID

/** Composite key: `(accountId, currency)` — the projection and balance-store key. */
data class AccountCurrency(val accountId: UUID, val currency: String)
