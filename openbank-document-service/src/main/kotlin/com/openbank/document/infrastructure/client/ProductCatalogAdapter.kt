// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import com.openbank.document.application.port.out.ProductCatalogPort
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.util.UUID

/**
 * **Fail-open** [ProductCatalogPort] adapter (mirrors `account-service`'s own fail-open
 * `ProductCatalogAdapter`): an unreachable/unknown product-catalog must never block issuing an
 * onboarding document from blocking anything downstream of it either — it just means no document
 * gets issued this time, logged for operators to notice, not a hard failure.
 */
@ApplicationScoped
class ProductCatalogAdapter : ProductCatalogPort {

    @Inject
    @RestClient
    lateinit var client: ProductCatalogClient

    private val log = Logger.getLogger(ProductCatalogAdapter::class.java)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findDocumentTemplateCode(productId: UUID): String? = try {
        client.getById(productId.toString()).awaitSuspending()
            .termsAndConditions.firstNotNullOfOrNull { it.documentTemplateCode }
    } catch (e: Exception) {
        log.warnf("product-catalog unavailable for %s; no onboarding document will be issued: %s", productId, e.message)
        null
    }
}
