// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.observability

import com.openbank.document.infrastructure.render.PdfBoxPadesSealAdapter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped

/**
 * Exports the institutional PAdES seal certificate's expiry (#16292), so the Keys & Certificates
 * page and the `SealCertificateExpiring` alert can see it. Before this, the keystore was read once
 * at boot and its notAfter existed nowhere outside the JVM: a real seal certificate could lapse
 * with nothing to notice it, and every document sealed afterwards would carry an invalid seal.
 *
 * One series, `openbank_document_seal_certificate_expiry_timestamp_seconds{identity=...}`, with
 * `identity` = `keystore` (the organizational certificate) or `ephemeral` (the DEV-ONLY fallback,
 * whose expiry is meaningless as evidence). The label lets the alert ignore the ephemeral case and
 * lets the page show it as a dev-only seal rather than as a healthy one.
 *
 * Public certificate metadata only. No key material, subject or serial is exported.
 *
 * @Startup so the gauge exists from boot, not from the first scrape that happens to touch the bean.
 */
@Startup
@ApplicationScoped
class SealCertificateExpiryMetrics(adapter: PdfBoxPadesSealAdapter, registry: MeterRegistry) {
    init {
        val identity = if (adapter.sealIdentityEphemeral) "ephemeral" else "keystore"
        Gauge.builder(METRIC, adapter) { it.sealCertificateNotAfter.epochSecond.toDouble() }
            .description("notAfter of the institutional PAdES seal certificate, Unix seconds")
            .tag("identity", identity)
            .register(registry)
    }

    companion object {
        const val METRIC = "openbank_document_seal_certificate_expiry_timestamp_seconds"
    }
}
