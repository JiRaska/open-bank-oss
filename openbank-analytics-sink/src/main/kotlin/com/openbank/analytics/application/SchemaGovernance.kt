// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.analytics.application

import com.openbank.analytics.application.port.out.SchemaCatalogSource
import com.openbank.libs.analytics.SchemaCatalog
import com.openbank.libs.analytics.SchemaKey
import jakarta.enterprise.context.ApplicationScoped
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Schema governance gate for ingested events (ADR-0023, F7).
 *
 * Loads a [SchemaCatalog] from the injected [SchemaCatalogSource] (config by default, Apicurio when
 * `openbank.analytics.schema.backend=apicurio`) and decides whether an incoming (eventType,
 * schemaVersion) is accepted. An unknown or newer-than-known schema is a governance gap (producer bug
 * / skipped migration / malformed event); when [strict] it is routed to the dead-letter quarantine
 * instead of silently corrupting the 10-year log of record.
 *
 * If the catalogue is empty the gate is **open** (accepts everything) so the service stays
 * offline-buildable and backwards-compatible — strict enforcement is opt-in via config.
 */
@ApplicationScoped
class SchemaGovernance {

    @Inject
    lateinit var catalogSource: SchemaCatalogSource

    @ConfigProperty(name = "openbank.analytics.schema.strict", defaultValue = "false")
    var strict: Boolean = false

    private val log = Logger.getLogger(SchemaGovernance::class.java)
    private var catalog: SchemaCatalog = SchemaCatalog(emptySet())
    private var governed = false

    @PostConstruct
    fun init() {
        catalog = catalogSource.load()
        governed = catalog.knownEventTypes().isNotEmpty()
        if (governed) {
            log.infof("schema governance enabled strict=%s knownTypes=%d", strict, catalog.knownEventTypes().size)
        } else {
            log.info("schema governance disabled (empty catalog) — accepting all schemas")
        }
    }

    /** True if the schema may be written. Unknown/newer schemas are rejected only when strict + governed. */
    fun accept(eventType: String, schemaVersion: Int): Boolean {
        if (!governed) return true
        val compatible = catalog.isCompatible(SchemaKey(eventType, schemaVersion))
        if (!compatible) {
            log.warnf("schema not in catalog eventType=%s schemaVersion=%d strict=%s", eventType, schemaVersion, strict)
            return !strict
        }
        return true
    }
}
