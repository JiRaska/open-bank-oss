// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.contract

import com.openbank.document.domain.DocumentTemplateSeed
import com.openbank.document.domain.model.TemplateStatus
import com.openbank.document.infrastructure.persistence.entity.DocumentTemplateEntity
import io.quarkus.vertx.VertxContextSupport
import org.hibernate.reactive.mutiny.Mutiny

/**
 * Pact's conditional happy path: an operator has published pension KID wording. Production
 * boot deliberately seeds its unreviewed wording as DRAFT. This fixture replaces that wording with
 * inert test-only text in the isolated provider database before publishing it; it is no evidence
 * of real legal approval and must never be called by production seeding.
 */
object PensionPublishedPactState {
    const val STATE = "a test-only published pension key-information fixture exists"

    private val templateId = DocumentTemplateSeed.templates.single {
        it.code == "pension-dps-key-information-cs" && it.status == TemplateStatus.DRAFT
    }.id

    fun publishInTestDatabase(sf: Mutiny.SessionFactory) {
        VertxContextSupport.subscribeAndAwait {
            sf.withTransaction { session ->
                session.find(DocumentTemplateEntity::class.java, templateId).map { template ->
                    checkNotNull(template) { "Pension KID draft must be seeded before Pact replay" }
                    check(template.status == TemplateStatus.DRAFT || template.status == TemplateStatus.PUBLISHED) {
                        "Pension KID fixture cannot publish a retired template"
                    }
                    template.bodyHtml = "<html><body><h1>PACT TEST KID</h1></body></html>"
                    template.status = TemplateStatus.PUBLISHED
                }
            }
        }
    }

    /** Restore the real unreviewed seed so the HTTP refusal test is independent of Pact order. */
    fun restoreDraftInTestDatabase(sf: Mutiny.SessionFactory) {
        VertxContextSupport.subscribeAndAwait {
            sf.withTransaction { session ->
                session.find(DocumentTemplateEntity::class.java, templateId).map { template ->
                    checkNotNull(template) { "Pension KID draft must be seeded before HTTP refusal test" }
                    template.bodyHtml = DocumentTemplateSeed.templates.single { it.id == templateId }.bodyHtml
                    template.status = TemplateStatus.DRAFT
                }
            }
        }
    }
}
