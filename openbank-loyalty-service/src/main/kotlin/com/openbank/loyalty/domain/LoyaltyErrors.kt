// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.loyalty.domain

/** The party asked about has no ledger — distinct from a party whose balance is zero. */
class LoyaltyNotFoundException(message: String) : RuntimeException(message)

/** A request that cannot be honoured in the current state (a released grant re-committed, say). */
class LoyaltyConflictException(message: String) : RuntimeException(message)
