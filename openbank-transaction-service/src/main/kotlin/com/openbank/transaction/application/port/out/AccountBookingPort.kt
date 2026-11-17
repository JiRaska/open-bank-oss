// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.transaction.application.port.out

import com.openbank.libs.market.BookingContext
import java.util.UUID

interface AccountBookingPort {
    suspend fun context(accountId: UUID, synthetic: Boolean): BookingContext?
}
