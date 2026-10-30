// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.document

import com.openbank.pension.application.onboarding.GeneratedDocument
import com.openbank.pension.application.onboarding.KeyInformationDocumentPort
import com.openbank.pension.application.onboarding.KidRequest
import com.openbank.pension.application.port.out.AnnualStatementContent
import com.openbank.pension.application.port.out.AnnualStatementDocumentPort
import com.openbank.pension.application.port.out.RenderedDocument
import com.openbank.pension.application.port.out.TaxCertificateDocumentPort
import com.openbank.pension.domain.incentive.TaxYearSummary
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.UUID

/** Template bases (`<base>-cs` / `<base>-en` in document-service). Sources: `document-templates/`. */
object PensionDocumentTemplates {
    const val TAX_CERTIFICATE = "pension-tax-certificate"
    const val ANNUAL_STATEMENT = "pension-annual-statement"
}

/** Builds the data maps; pure, so the template variables they feed are tested against the sources. */
object PensionDocumentData {
    fun kid(request: KidRequest): Map<String, Any?> = mapOf(
        "applicationId" to request.applicationId.toString(),
        "productLine" to request.productLine.name,
        "documentType" to request.type.name,
        "strategyCode" to request.strategyCode,
    )

    fun taxCertificate(summary: TaxYearSummary, contractReference: String): Map<String, Any?> = mapOf(
        "contractReference" to contractReference,
        "taxYear" to summary.taxYear,
        "currency" to summary.currency,
        "participantContributions" to summary.participantContributions.toPlainString(),
        "employerContributions" to summary.employerContributions.toPlainString(),
        "stateIncentives" to summary.stateIncentives.toPlainString(),
        "deductibleAmount" to summary.deductibleAmount.toPlainString(),
        "employerExempt" to summary.employerExempt.toPlainString(),
        "employerTaxable" to summary.employerTaxable.toPlainString(),
    )

    fun annualStatement(content: AnnualStatementContent): Map<String, Any?> = mapOf(
        "contractReference" to content.contractReference,
        "year" to content.summary.taxYear,
        "currency" to content.summary.currency,
        "participantContributions" to content.summary.participantContributions.toPlainString(),
        "employerContributions" to content.summary.employerContributions.toPlainString(),
        "stateIncentives" to content.summary.stateIncentives.toPlainString(),
        "transferIn" to content.summary.transferIn.toPlainString(),
        "value" to content.value.toPlainString(),
        "valueAsOf" to content.valueAsOf.toString(),
    )
}

/** The real document adapters' shared rendering, over the injected REST client. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class DocumentServiceRenderer {
    @Inject
    @RestClient
    lateinit var client: DocumentServiceRestClient

    val rendering: DocumentRendering by lazy { DocumentRendering { client.render(it) } }
}

/**
 * The REAL [KeyInformationDocumentPort] (#12379): pre-contractual information for DPS, the PRIIPs
 * KID for DIP — the pack names the template. The returned SHA-256 is document-service's hash of the
 * stored document; `OnboardingService` binds the SCA challenge to exactly it, so what the
 * participant signs is what document-service will serve back.
 */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class DocumentServiceKeyInformationAdapter(private val renderer: DocumentServiceRenderer) : KeyInformationDocumentPort {
    override suspend fun generate(request: KidRequest): GeneratedDocument {
        val doc = renderer.rendering.render(
            templateBase = request.templateCode,
            language = request.language,
            data = PensionDocumentData.kid(request),
            partyRef = request.partyId.toString(),
            caseRef = request.applicationId.toString(),
            productRef = "pension-${request.productLine.name.lowercase()}",
        )
        return GeneratedDocument(doc.documentId, doc.sha256)
    }
}

/** The REAL [TaxCertificateDocumentPort]: the annual certificate for the participant's tax return. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class DocumentServiceTaxCertificateAdapter(
    private val renderer: DocumentServiceRenderer,
    @param:ConfigProperty(name = "openbank.pension.documents.language", defaultValue = "cs")
    private val language: String,
) : TaxCertificateDocumentPort {
    override suspend fun generate(
        summary: TaxYearSummary,
        participantPartyId: UUID,
        contractReference: String,
    ): String = renderer.rendering.render(
        templateBase = PensionDocumentTemplates.TAX_CERTIFICATE,
        language = language,
        data = PensionDocumentData.taxCertificate(summary, contractReference),
        partyRef = participantPartyId.toString(),
        caseRef = "${summary.contractId}:${summary.taxYear}",
        productRef = "pension",
    ).documentId
}

/** The REAL [AnnualStatementDocumentPort]. */
@UnlessBuildProfile(anyOf = ["dev", "test"])
@ApplicationScoped
class DocumentServiceAnnualStatementAdapter(
    private val renderer: DocumentServiceRenderer,
    @param:ConfigProperty(name = "openbank.pension.documents.language", defaultValue = "cs")
    private val language: String,
) : AnnualStatementDocumentPort {
    override suspend fun generate(content: AnnualStatementContent): RenderedDocument = renderer.rendering.render(
        templateBase = PensionDocumentTemplates.ANNUAL_STATEMENT,
        language = language,
        data = PensionDocumentData.annualStatement(content),
        partyRef = content.participantPartyId.toString(),
        caseRef = "${content.summary.contractId}:${content.summary.taxYear}",
        productRef = "pension",
    )
}
