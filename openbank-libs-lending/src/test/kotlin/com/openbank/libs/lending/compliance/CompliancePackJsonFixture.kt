// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * TEST-ONLY JSON front-end, so the decoder suites can keep expressing their fixtures as JSON
 * documents while [CompliancePackParser] itself stays Jackson-free (#3670, ADR-0122).
 *
 * The production front-end is `CompliancePackJson` in **openbank-lending-service**. This is a
 * deliberate second copy of three lines, not shared code: a libs module must not depend on
 * a service (the direction ADR-0122 forbids), and the alternative — moving the
 * 180-line decoder suite into the service — would take the tests away from the code
 * they cover. Nothing here is on a production path; `check-domain-purity.py` scans
 * `src/main/kotlin` only, and this file is under `src/test`.
 *
 * Declared as an extension on [CompliancePackParser] so the existing call sites read exactly
 * as they did before the split.
 */
private val fixtureMapper: ObjectMapper = ObjectMapper().registerKotlinModule()

internal fun CompliancePackParser.fromJson(json: String): CompliancePack = fromMap(fixtureMapper.readValue(json))
