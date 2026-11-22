// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.metrics

import com.openbank.fetchbroker.application.port.out.FetchMetricsPort
import com.openbank.fetchbroker.domain.FetchOutcome
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration

/**
 * `openbank_web_fetch_total{outcome,host}` (ADR-0387 D8 / ADR-0253) plus the shared workflow
 * liveness gauge (ADR-0237). `@Startup` so the gauge exists from boot, not from the first call.
 */
@Startup
@ApplicationScoped
class MicrometerFetchMetrics(private val registry: MeterRegistry, private val domainMetrics: DomainMetrics) :
    FetchMetricsPort {
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun registerLiveness() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    override fun recorded(outcome: FetchOutcome, host: String) {
        Counter.builder(METRIC)
            .description("web.fetch calls by outcome and charter-listed host")
            .tag("outcome", outcome.name.lowercase())
            .tag("host", host)
            .register(registry)
            .increment()
    }

    override fun fetchSucceeded() {
        liveness?.recordSuccess()
    }

    companion object {
        /** Prometheus renders this as `openbank_web_fetch_total`. */
        const val METRIC = "openbank.web.fetch"
        const val WORKFLOW_NAME = "web-fetch"

        /** The first consumer re-reads its sources daily; the alert fires at 2x. */
        val EXPECTED_INTERVAL: Duration = Duration.ofDays(1)
    }
}
