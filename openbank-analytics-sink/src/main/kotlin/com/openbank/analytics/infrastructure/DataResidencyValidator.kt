// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.infrastructure

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Data-residency guard verified at startup (ADR-0023, F9 / GDPR Art. 44 + DORA).
 *
 * Banking personal data must stay in the approved region(s). The warehouse holds a 10-year PII-masked
 * (but pseudonymous) store, so it is in scope. This validator fails fast at boot if the configured
 * ClickHouse region is not on the allow-list, turning a silent mis-deployment into a deploy-time error.
 *
 * Configuration:
 *  - `openbank.analytics.residency.region` — the region this warehouse runs in (default `eu-north-1`).
 *  - `openbank.analytics.residency.allowed` — comma-separated allow-list (default `eu-north-1`).
 *  - `openbank.analytics.residency.enforce` — when true (default), a violation aborts startup; when
 *    false it only logs (useful for local dev / `%dev`).
 *
 * `eu-north-1` is the estate region decided by ADR-0175 §Decision 1. It is decided *there* and not in
 * `openbank-infra/aws/README.md`, whose `eu-central-1` production pin ADR-0175 §Decision 2 names as an
 * inference from a condition ADR-0027 does not contain, and explicitly does not adopt.
 *
 * Until #3962 these defaults were `eu-central-1` with an allow-list of `eu-central-1,eu-west-1`, which
 * left exactly one bootable configuration and it was the one that misstates where the warehouse is:
 * the guard passed on `region=eu-central-1` for a warehouse in `eu-north-1`, while configuring the real
 * region aborted boot (`enforce` defaults to true, and `eu-north-1` was not on the list).
 *
 * KNOWN LIMIT (deliberate, not an oversight): this compares a config string to a config string. It
 * never observes the region it is actually running in (IMDS, `AWS_REGION`, the bucket's
 * `LocationConstraint`), so it can only detect a self-inconsistent configuration, not a real residency
 * violation. Nothing else in the service reads `residency.region`; a wrong value is a false assurance
 * in the log line, not a mis-routed write.
 */
@ApplicationScoped
class DataResidencyValidator {

    @ConfigProperty(name = "openbank.analytics.residency.region", defaultValue = "eu-north-1")
    lateinit var region: String

    @ConfigProperty(name = "openbank.analytics.residency.allowed", defaultValue = "eu-north-1")
    lateinit var allowed: String

    @ConfigProperty(name = "openbank.analytics.residency.enforce", defaultValue = "true")
    var enforce: Boolean = true

    private val log = Logger.getLogger(DataResidencyValidator::class.java)

    fun onStart(@Observes event: StartupEvent) {
        val allowList = allowed.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val current = region.trim().lowercase()
        if (current in allowList) {
            log.infof("data residency OK: region=%s allowed=%s", current, allowList)
            return
        }
        val message = "data residency violation: region '$current' is not in the allow-list $allowList " +
            "(GDPR Art. 44 / DORA). Set openbank.analytics.residency.region to an approved region."
        if (enforce) {
            throw IllegalStateException(message)
        } else {
            log.warn("$message (enforcement disabled)")
        }
    }
}
