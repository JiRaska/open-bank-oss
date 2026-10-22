// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.port.out

import java.time.Instant

/** Read-only refund monitoring; independent of aggregate commands and lifecycle persistence. */
interface SepaRefundQuery {
    suspend fun oldestUnresolvedReturnAt(): Instant?
}
