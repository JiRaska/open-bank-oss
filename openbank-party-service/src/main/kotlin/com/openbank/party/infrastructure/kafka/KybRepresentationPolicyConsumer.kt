// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.messaging.EventRetry
import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.EligibleRepresentative
import com.openbank.party.domain.model.RepresentationPolicyMode
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/** Projects only signed, attested KYB rules. Legacy and incomplete cases gain no authority. */
@ApplicationScoped
class KybRepresentationPolicyConsumer(
    private val policies: RepresentationPolicyRepository,
    private val mapper: ObjectMapper,
) {
    private val log = Logger.getLogger(KybRepresentationPolicyConsumer::class.java)

    @Incoming("kyb-representation-in")
    suspend fun consume(payload: String) {
        val root = mapper.readTree(payload)
        if (root.path("eventType").asText() != "BUSINESS_AGREEMENT_SIGNED") return
        val rule = root.path("statutoryPolicy")
        if (rule.isMissingNode || rule.isNull) return // pre-rollout event or manually overridden rule
        val snapshot = parse(root, rule)
        EventRetry.withRetry(log, "KYB statutory rule projection", snapshot.sourceCaseId) {
            val existing = policies.findBySourceCaseId(snapshot.sourceCaseId)
            if (existing != null) {
                require(snapshot.copy(id = existing.id, revision = existing.revision) == existing) {
                    "conflicting statutory evidence for KYB case ${snapshot.sourceCaseId}"
                }
            } else {
                policies.insert(snapshot.copy(revision = policies.allocateRevision()))
            }
        }
    }

    private fun parse(root: JsonNode, rule: JsonNode): RepresentationPolicySnapshot {
        val caseId = root.requiredUuid("caseId")
        val principalId = root.requiredUuid("entityPartyId")
        require(root.path("sourceService").asText() == "kyb-service") { "unexpected statutory evidence producer" }
        require(root.path("status").asText() in setOf("SIGNED", "ACTIVE")) { "unsigned statutory evidence" }
        require(rule.requiredUuid("sourceCaseId") == caseId) { "statutory evidence case mismatch" }
        require(rule.requiredUuid("principalPartyId") == principalId) { "statutory evidence principal mismatch" }
        val quorum = rule.requiredInt("requiredSignatures")
        require(root.requiredInt("requiredSignatures") == quorum) { "statutory evidence quorum mismatch" }
        require(root.requiredInt("signedCount") >= quorum) { "statutory evidence lacks signatures" }
        return RepresentationPolicySnapshot(
            id = UUID.nameUUIDFromBytes("kyb-statutory-policy:$caseId".toByteArray(Charsets.UTF_8)),
            principalPartyId = principalId,
            revision = 1, // replaced by the repository's global sequence before insertion
            sourceCaseId = caseId,
            attestationId = rule.requiredUuid("attestationId"),
            ruleTextHash = rule.requiredText("ruleTextHash"),
            registrySource = rule.requiredText("registrySource"),
            registrySourceRef = rule.path("registrySourceRef").takeUnless { it.isNull || it.isMissingNode }?.asText(),
            registryRepresentativeCount = rule.requiredInt("registryRepresentativeCount"),
            mode = RepresentationPolicyMode.valueOf(rule.requiredText("mode")),
            requiredSignatures = quorum,
            requiredOffices = rule.requiredStringList("requiredOffices"),
            eligibleRepresentatives = rule.path("eligibleRepresentatives").map { representative ->
                EligibleRepresentative(
                    partyId = representative.requiredUuid("partyId"),
                    registryRepresentativeIndices = representative.requiredIntSet("registryRepresentativeIndices"),
                    officeTags = representative.requiredStringList("officeTags").toSet(),
                )
            },
            evidenceRef = rule.requiredText("evidenceRef"),
            effectiveFrom = Instant.parse(rule.requiredText("effectiveFrom")),
        )
    }

    private fun JsonNode.requiredText(name: String): String = path(name).asText().also {
        require(it.isNotBlank()) { "missing statutory evidence field $name" }
    }

    private fun JsonNode.requiredUuid(name: String): UUID = UUID.fromString(requiredText(name))

    private fun JsonNode.requiredInt(name: String): Int = path(name).also {
        require(it.isIntegralNumber) { "missing statutory evidence integer $name" }
    }.intValue()

    private fun JsonNode.requiredStringList(name: String): List<String> = path(name).also {
        require(it.isArray && it.all { item -> item.isTextual && item.asText().isNotBlank() }) {
            "invalid statutory evidence list $name"
        }
    }.map { it.asText() }

    private fun JsonNode.requiredIntSet(name: String): Set<Int> = path(name).also {
        require(it.isArray && it.all(JsonNode::isIntegralNumber)) { "invalid registry indices" }
    }.map { it.intValue() }.toSet()
}
