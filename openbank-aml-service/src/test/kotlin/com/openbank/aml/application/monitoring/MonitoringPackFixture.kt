// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.application.monitoring

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.aml.domain.monitoring.CounterScheme
import com.openbank.aml.infrastructure.monitoring.CounterSchemeLoader
import com.openbank.aml.infrastructure.monitoring.MonitoringPackLoader
import java.io.File

/** The shipped synthetic monitoring files, loaded exactly as the service loads them. */
object MonitoringPackFixture {
    val mapper = ObjectMapper()

    fun file(name: String): String = File("src/main/resources/aml/$name").readText()

    val scheme: CounterScheme = CounterSchemeLoader.parse(mapper, file("monitoring-counter-scheme.synthetic.json"))

    fun load(
        pack: String = file("monitoring-pack.synthetic.json"),
        reasons: String = file("monitoring-reasons.synthetic.json"),
        severities: String = file("monitoring-severities.synthetic.json"),
        features: String = file("monitoring-count-features.synthetic.json"),
        scheme: CounterScheme = this.scheme,
    ): MonitoringPack = MonitoringPackLoader.load(mapper, scheme, pack, reasons, severities, features)
}
