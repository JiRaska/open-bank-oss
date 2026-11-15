// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.application

import com.openbank.productcatalog.domain.catalog.CatalogValue
import com.openbank.productcatalog.domain.catalog.ProductRevision
import com.openbank.productcatalog.domain.catalog.RevisionApprovalPolicy
import com.openbank.productcatalog.domain.catalog.isCatalogPrincipalKey

/** The same checks apply on the initial read and again inside the publication or review lock. */
internal fun RevisionApprovalPolicy.requireApprovable(draft: ProductRevision) {
    requireVerifiedActor(draft.makerId, "maker")
    if (requireEffectiveFrom && draft.effectiveFrom == null) {
        throw CatalogConflictException("approval requires an explicit effectiveFrom")
    }
    val attributes = draft.content.attributes.values
    requiredAttributes.forEach { (name, expected) ->
        if (attributes[name] != CatalogValue.TextValue(expected)) {
            throw CatalogConflictException("approval requires attribute $name = $expected")
        }
    }
}

internal fun RevisionApprovalPolicy.requireVerifiedActor(actor: String, role: String) {
    if (requireStablePrincipal && !isCatalogPrincipalKey(actor)) {
        throw CatalogForbiddenException("this revision requires verified $role identity")
    }
}
