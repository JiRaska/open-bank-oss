// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * JSON front-end for [CompliancePackParser] — the adapter half of the port/adapter split
 * ADR-0122 asks for (#3670). All decoding rules, the closed schema and every fail-closed
 * rejection live in [CompliancePackParser.fromMap] over in **openbank-libs-domain**; the
 * only thing here is Jackson turning a `String` into the `Map` that decoder consumes.
 *
 * This file exists because Jackson is a framework dependency and libs-domain must have
 * none: `CompliancePackParser.fromJson` used to sit in the domain module and was one of
 * the eight Jackson entries in `domain-purity-baseline.txt`. Adding a YAML (or CBOR, or
 * Protobuf) front-end means a sibling object here, never an edit to the domain decoder.
 */
object CompliancePackJson {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Strict, fail-closed: a malformed document or an unknown key rejects the pack whole. */
    fun fromJson(json: String): CompliancePack = CompliancePackParser.fromMap(mapper.readValue(json))
}
