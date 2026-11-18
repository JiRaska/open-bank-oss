// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.fetchbroker.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.fetchbroker.application.FetchCollaborators
import com.openbank.fetchbroker.application.HostRateLimiter
import com.openbank.fetchbroker.application.WebFetchService
import com.openbank.fetchbroker.application.port.out.FetchAuditPort
import com.openbank.fetchbroker.application.port.out.FetchMetricsPort
import com.openbank.fetchbroker.infrastructure.charter.AgentsYamlCharterHosts
import com.openbank.fetchbroker.infrastructure.render.HttpTransport
import com.openbank.fetchbroker.infrastructure.render.KitesurfRenderer
import com.openbank.fetchbroker.infrastructure.render.PdfTextExtractor
import com.openbank.fetchbroker.infrastructure.render.PlainHttpRenderer
import com.openbank.fetchbroker.infrastructure.render.RobotsGate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration
import java.util.Optional

/** Wires the framework-free use case from config (ADR-0387). */
@ApplicationScoped
class FetchBrokerProducer {
    @ConfigProperty(name = "openbank.fetch-broker.charters-resource")
    lateinit var chartersResource: String

    @ConfigProperty(name = "openbank.fetch-broker.user-agent")
    lateinit var userAgent: String

    @ConfigProperty(name = "openbank.fetch-broker.max-response-bytes")
    lateinit var maxResponseBytes: java.lang.Integer

    @ConfigProperty(name = "openbank.fetch-broker.max-content-chars")
    lateinit var maxContentChars: java.lang.Integer

    @ConfigProperty(name = "openbank.fetch-broker.per-host-min-interval")
    lateinit var perHostMinInterval: Duration

    /** Absent until the JS renderer is provisioned; absence ⇒ NOT_RENDERABLE for `render: js` hosts. */
    @ConfigProperty(name = "openbank.fetch-broker.kitesurf.base-url")
    lateinit var kitesurfBaseUrl: Optional<String>

    /** Mounted from the `browser-run-api-token` secret, into this pod only (ADR-0387 D7). */
    @ConfigProperty(name = "openbank.fetch-broker.kitesurf.api-token")
    lateinit var kitesurfApiToken: Optional<String>

    /** Off by default: in-process PDFBox is not bounded (see PdfTextExtractor). */
    @ConfigProperty(name = "openbank.fetch-broker.pdf.enabled", defaultValue = "false")
    lateinit var pdfEnabled: java.lang.Boolean

    @Inject
    lateinit var mapper: ObjectMapper

    @Produces
    @ApplicationScoped
    fun webFetchService(metrics: FetchMetricsPort, audit: FetchAuditPort): WebFetchService {
        val transport = HttpTransport.safe(maxResponseBytes.toInt())
        val robots = RobotsGate(transport, userAgent)
        return WebFetchService(
            FetchCollaborators(
                charters = AgentsYamlCharterHosts.fromClasspath(chartersResource),
                plain = PlainHttpRenderer(
                    transport,
                    robots,
                    userAgent,
                    if (pdfEnabled.booleanValue()) PdfTextExtractor() else null,
                ),
                js = KitesurfRenderer(
                    kitesurfBaseUrl.orElse(null),
                    kitesurfApiToken.orElse(null),
                    transport,
                    robots,
                    mapper,
                ),
                metrics = metrics,
                audit = audit,
                rateLimiter = HostRateLimiter(perHostMinInterval),
            ),
            maxContentChars.toInt(),
        )
    }
}
