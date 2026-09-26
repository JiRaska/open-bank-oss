// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.lending.application.port.out.CompliancePackCodec
import com.openbank.libs.lending.compliance.CompliancePack
import com.openbank.libs.lending.compliance.CompliancePackParser
import jakarta.enterprise.context.ApplicationScoped

/**
 * JSON front-end for [CompliancePackParser] — the adapter half of the port/adapter split
 * ADR-0122 asks for (#3670). All decoding rules, the closed schema and every fail-closed
 * rejection live in [CompliancePackParser.fromMap] over in **openbank-libs-lending**; the
 * only thing here is Jackson turning a `String` into the `Map` that decoder consumes.
 *
 * This file exists because Jackson is a framework dependency and libs-domain must have
 * none: `CompliancePackParser.fromJson` used to sit in the domain module and was one of
 * the eight Jackson entries in `domain-purity-baseline.txt`. Adding a YAML (or CBOR, or
 * Protobuf) front-end means a sibling object here, never an edit to the domain decoder.
 *
 * It lived in openbank-libs-runtime until ADR-0317 phase 1; lending-service is its only
 * consumer, so it moved here rather than keep libs-runtime depending on the lending module.
 *
 * Implements the [CompliancePackCodec] outbound port (ADR-0002) so the application layer
 * (`CompliancePackActivationService`) can depend on the interface instead of this concrete
 * infrastructure class. A CDI bean, injected wherever the port is needed; the companion's
 * static `fromJson` remains for the callers already inside this infrastructure package
 * (`CompliancePackRefresher`) and tests, where depending on the concrete adapter directly is
 * not a layering violation.
 */
@ApplicationScoped
class CompliancePackJson : CompliancePackCodec {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Strict, fail-closed: a malformed document or an unknown key rejects the pack whole. */
    override fun fromJson(json: String): CompliancePack = CompliancePackParser.fromMap(mapper.readValue(json))

    companion object {
        private val default = CompliancePackJson()

        /** Static convenience for infrastructure-internal callers; identical decoding logic. */
        fun fromJson(json: String): CompliancePack = default.fromJson(json)
    }
}
