// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application.port.out

import com.openbank.libs.analytics.SchemaCatalog

/**
 * Source of the governed [SchemaCatalog] for the ingest schema gate (ADR-0023, F7).
 *
 * Decouples *where the catalogue of accepted (eventType, schemaVersion) pairs comes from* from the
 * *gate decision* in [com.openbank.analytics.application.SchemaGovernance]. The default binding
 * [com.openbank.analytics.infrastructure.schema.ConfigSchemaCatalogSource] reads a static config spec
 * (offline-buildable); the durable binding
 * [com.openbank.analytics.infrastructure.schema.ApicurioSchemaCatalogSource] loads the registered
 * artifacts/versions from an Apicurio schema registry.
 *
 * [load] is synchronous (called once at startup): an empty catalogue means *ungoverned* (gate open).
 */
interface SchemaCatalogSource {
    fun load(): SchemaCatalog
}
