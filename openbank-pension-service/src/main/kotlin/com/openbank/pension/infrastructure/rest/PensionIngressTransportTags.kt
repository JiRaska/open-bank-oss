// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

import io.micrometer.core.instrument.Tags
import io.quarkus.micrometer.runtime.HttpServerMetricsTagsContributor
import jakarta.enterprise.context.ApplicationScoped

/** A bounded listener signal for the staged inbound mTLS migration (#12570). */
@ApplicationScoped
class PensionIngressTransportTags : HttpServerMetricsTagsContributor {
    override fun contribute(context: HttpServerMetricsTagsContributor.Context): Tags =
        Tags.of("pension_transport", if (context.request().isSSL) "tls" else "plain")
}
