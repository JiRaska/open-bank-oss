// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.observability

import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped

@Startup
@ApplicationScoped
class CaseCoordinatorMetricsBootstrap(
    private val state: CaseKillSwitchStatePort,
    private val metrics: CaseCoordinatorMetricsService,
) {
    @PostConstruct
    fun initialize() {
        state.activeScopes().forEach { metrics.setKillSwitchActive(it.scope, true) }
    }
}
