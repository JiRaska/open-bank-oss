// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.infrastructure.client

import com.openbank.domestic.application.port.out.PaymentConfirmationRenderPort
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Test double for [PaymentConfirmationRenderPort] — replaces [PaymentConfirmationRenderAdapter]
 * (which would otherwise call a real document-service over HTTP) in `@QuarkusTest`, mirroring
 * [com.openbank.domestic.infrastructure.temporal.WorkflowClientTestProducer]'s
 * `@Alternative @Priority(1)` CDI-override pattern. Tests set [renderedHtml] and read
 * [lastTemplateCode]/[lastData] to assert on what the use case asked for, without a running
 * document-service. Both are plain `var`s (no `private set`): the Quarkus Kotlin all-open plugin
 * makes every property of an `@ApplicationScoped` bean `open` for CDI proxying, and ktlint/kotlinc
 * reject a `private set` on an `open` property.
 */
@ApplicationScoped
@Alternative
@Priority(1)
class FakePaymentConfirmationRenderPort : PaymentConfirmationRenderPort {

    var renderedHtml: String = "<html>fake-confirmation</html>"
    var lastTemplateCode: String? = null
    var lastData: Map<String, Any?>? = null

    override suspend fun renderConfirmation(templateCode: String, data: Map<String, Any?>): String {
        lastTemplateCode = templateCode
        lastData = data
        return renderedHtml
    }
}
