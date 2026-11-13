// SPDX-License-Identifier: AGPL-3.0-only

package com.openbank.devops.application.port.out

import java.time.Instant

interface GitHubReadBudget {
    suspend fun reserve(observer: Boolean): Boolean
    suspend fun deferUntil(until: Instant)
}
