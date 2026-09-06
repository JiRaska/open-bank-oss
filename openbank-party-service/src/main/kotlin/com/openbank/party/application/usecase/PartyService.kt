// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.application.usecase

import com.openbank.libs.api.pagination.CursorEncoder
import com.openbank.libs.api.pagination.CursorPage
import com.openbank.libs.api.pagination.PageInfo
import com.openbank.libs.api.search.SearchRequest
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.identity.BlindIndex
import com.openbank.libs.identity.RodneCislo
import com.openbank.libs.observability.DomainMetrics
import com.openbank.party.application.port.`in`.*
import com.openbank.party.application.port.out.*
import com.openbank.party.domain.model.*
import com.openbank.party.domain.model.PartyDocumentFile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

private const val RC_KEY_VERSION = 1

/** A party in either terminal state is outside the world a mandate describes (ADR-0284 D3). */
private val MANDATE_INELIGIBLE = setOf(PartyStatus.CLOSED, PartyStatus.MERGED)

class PartyNotFoundException(id: UUID) : RuntimeException("Party not found: $id")
class PartyAlreadyExistsException(email: String) : RuntimeException("Party with email already exists: $email")
class PartyKeycloakSubAlreadyBoundException(sub: String) : RuntimeException("Keycloak sub already registered: $sub")

/** ADR-0179: a merge precondition failed. Carries an operator-readable reason (mapped to 409). */
class PartyMergeRejectedException(message: String) : RuntimeException(message)

@ApplicationScoped
class PartyService : PartyUseCase {

    @Inject lateinit var partyRepo: PartyRepository

    @Inject lateinit var documentRepo: PartyDocumentRepository

    @Inject lateinit var documentFileRepo: PartyDocumentFileRepository

    @Inject lateinit var payeeRepo: PartyPayeeRepository

    @Inject lateinit var mandateRepo: PartyMandateRepository

    @Inject lateinit var gdprAggregation: GdprAggregationPort

    @Inject lateinit var portabilityAggregation: PortabilityAggregationPort

    @Inject lateinit var accountGuard: PartyAccountGuardPort

    @Inject lateinit var marketingConsentForwarding: MarketingConsentForwardingPort

    @Inject lateinit var marketingConsentTracking: MarketingConsentTrackingRepository

    @Inject lateinit var metrics: DomainMetrics

    @Inject lateinit var changeMetrics: PartyChangeMetricsPort

    @Inject lateinit var clock: Clock

    /** ADR-0072: pepper for RČ blind index. Optional — dedup is silently skipped when absent. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "openbank.identity.rc-pepper")
    lateinit var rcPepper: Optional<String>

    override suspend fun createParty(cmd: CreatePartyCommand): Party {
        partyRepo.findByEmail(cmd.email)?.let {
            throw PartyAlreadyExistsException(cmd.email)
        }
        // Explicit id (ADR-0069 §B1): self-service onboarding binds party id == Keycloak sub.
        // A repeated create with the same id must not blow up with a PK violation — return the
        // existing record instead (the email check above already catches most retries).
        cmd.id?.let { requested ->
            partyRepo.findById(requested)?.let { return it }
        }
        val (rcIndex, rcKeyVer) = computeRcBlindIndex(cmd.taxId)
        val consentCapturedAt = if (cmd.consentGdpr != null || cmd.consentMarketing != null) {
            Instant.now(clock)
        } else {
            null
        }
        val party = Party(
            id = cmd.id ?: UUID.randomUUID(),
            // B1 invariant for self-service onboarding: id == Keycloak sub. Mirror it into
            // keycloakSub so sub-keyed lookups (getMyParty, legacy mobile path) agree.
            keycloakSub = cmd.id?.toString(),
            partyType = cmd.partyType,
            classification = cmd.classification,
            status = PartyStatus.PENDING_KYC,
            legalName = cmd.legalName,
            tradingName = cmd.tradingName,
            dateOfBirth = cmd.dateOfBirth,
            nationality = cmd.nationality,
            taxId = cmd.taxId,
            registrationNumber = cmd.registrationNumber,
            legalForm = cmd.legalForm,
            registrationCountry = cmd.registrationCountry,
            email = cmd.email,
            phone = cmd.phone,
            address = cmd.address,
            kycStatus = KycStatus.NOT_STARTED,
            createdAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
            rcBlindIndex = rcIndex,
            rcIndexKeyVersion = rcKeyVer,
            consentGdpr = cmd.consentGdpr,
            consentMarketing = cmd.consentMarketing,
            consentCapturedAt = consentCapturedAt,
        )
        val saved = partyRepo.save(
            party,
            PartyEvents.created(party, Instant.now(clock), PartyActor.system("party-api")),
        )
        metrics.partyCreated(saved.partyType.name)
        return saved
    }

    /**
     * Forwards the toggle to consent-service (ADR-0198 D3, ADR-0206 D5) instead of writing
     * `consentMarketing` here — [MarketingConsentProjectionService] (ADR-0205 D4) is the sole
     * writer of that column, driven by consent-service's own ConsentGranted/Revoked events, so
     * this path can never race it into a split brain. The returned [Party] reflects the caller's
     * now-accepted intent optimistically (consent-service confirmed synchronously via ADR-0205
     * D1's auto-activate path); the persisted row catches up asynchronously over Kafka, typically
     * within milliseconds.
     */
    /**
     * Pay-to-phone lookup. The caller sends hashes it computed from numbers it already holds; this
     * returns the subset that belongs to a party who opted in. Nothing about a MISS is recorded —
     * no log line, no table — because the set of numbers a customer knows is itself personal data
     * and the bank has no business accumulating it.
     *
     * [MAX_DIRECTORY_LOOKUP] bounds a single call. It is not a privacy control on its own (a
     * caller can page), it is what stops one request from turning into an unbounded IN-list; the
     * privacy control is [Party.discoverable].
     */
    override suspend fun lookupByPhoneHashes(hashes: Collection<String>): List<PhoneDirectoryMatch> {
        val clean = hashes.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length == SHA256_HEX_LENGTH && it.all { c -> c.isDigit() || c in 'a'..'f' } }
            .distinct()
            .take(MAX_DIRECTORY_LOOKUP)
            .toList()
        if (clean.isEmpty()) return emptyList()
        return partyRepo.findDiscoverableByPhoneHashes(clean).mapNotNull { p ->
            p.phone?.let { PhoneDirectory.hash(it) }?.let { h -> PhoneDirectoryMatch(h, p.id, p.legalName) }
        }
    }

    override suspend fun updateDiscoverable(partyId: UUID, discoverable: Boolean): Boolean =
        partyRepo.updateDiscoverable(partyId, discoverable, Instant.now(clock))

    override suspend fun updateMarketingConsent(cmd: UpdateMarketingConsentCommand): Party {
        val party = partyRepo.findById(cmd.id) ?: throw PartyNotFoundException(cmd.id)
        if (cmd.marketingConsent) {
            marketingConsentForwarding.grant(cmd.id)
        } else {
            val tracked = marketingConsentTracking.findByPartyId(cmd.id)
            if (tracked != null) {
                marketingConsentForwarding.revoke(cmd.id, tracked.consentId, "customer opted out")
            }
            // No tracked consent: already off from consent-service's point of view — nothing to
            // revoke. Still returns the toggled-off Party below so the response matches intent.
        }
        return party.copy(consentMarketing = cmd.marketingConsent, consentMarketingUpdatedAt = Instant.now(clock))
    }

    /** Computes the RČ blind index when the pepper is configured and [taxId] is a valid Czech RČ. */
    private fun computeRcBlindIndex(taxId: String?): Pair<String?, Int?> {
        if (taxId == null) return null to null
        val pepper = rcPepper.filter { it.isNotBlank() }.orElse(null) ?: return null to null
        val rc = RodneCislo.parse(taxId)
        if (rc !is RodneCislo.Parsed) return null to null
        return BlindIndex.compute(pepper.toByteArray(Charsets.UTF_8), rc.canonical) to RC_KEY_VERSION
    }

    override fun isDedupAvailable(): Boolean = rcPepper.filter { it.isNotBlank() }.isPresent

    override suspend fun resolvePartyByRc(cmd: ResolvePartyByRcCommand): Party? {
        val pepper = rcPepper.filter { it.isNotBlank() }.orElse(null) ?: return null
        val rc = RodneCislo.parse(cmd.rawRc)
        if (rc !is RodneCislo.Parsed) return null
        val index = BlindIndex.compute(pepper.toByteArray(Charsets.UTF_8), rc.canonical)
        return partyRepo.findByRcBlindIndex(index)
    }

    override suspend fun getParty(id: UUID): Party = partyRepo.findById(id) ?: throw PartyNotFoundException(id)

    // ── Representation mandates (ADR-0284 D3) ────────────────────────────────────────────────

    override suspend fun grantMandate(cmd: GrantMandateCommand): PartyMandate {
        val principal = partyRepo.findById(cmd.principalPartyId) ?: throw PartyNotFoundException(cmd.principalPartyId)
        val agent = partyRepo.findById(cmd.agentPartyId) ?: throw PartyNotFoundException(cmd.agentPartyId)
        if (principal.partyType == PartyType.INDIVIDUAL) {
            throw PartyMandateRejectedException(
                "principal ${principal.id} is an INDIVIDUAL — only a legal entity can be acted for",
            )
        }
        if (agent.partyType != PartyType.INDIVIDUAL) {
            throw PartyMandateRejectedException(
                "agent ${agent.id} is a ${agent.partyType} — only a natural person can hold a mandate",
            )
        }
        listOf(agent, principal).firstOrNull { it.status in MANDATE_INELIGIBLE }?.let {
            throw PartyMandateRejectedException("party ${it.id} is ${it.status} and cannot take part in a mandate")
        }
        val now = Instant.now(clock)
        val existing = mandateRepo.findActive(principal.id, agent.id, cmd.role.name)
        val mandate = (
            existing ?: PartyMandate(
                // ADR-0106: a durable, indexed identifier — UUIDv7 for insert locality, not a v4.
                id = Ids.newId(),
                principalPartyId = principal.id,
                agentPartyId = agent.id,
                role = cmd.role,
                authority = cmd.authority,
                source = cmd.source,
                status = MandateStatus.ACTIVE,
                evidenceRef = cmd.evidenceRef,
                validFrom = now,
                validTo = cmd.validTo,
                createdAt = now,
                updatedAt = now,
            )
            ).copy(
            authority = cmd.authority,
            source = cmd.source,
            evidenceRef = cmd.evidenceRef ?: existing?.evidenceRef,
            validTo = cmd.validTo,
            updatedAt = now,
        )
        val event = PartyEvents.mandateGranted(mandate, now, PartyActor.system("party-api"))
        return if (existing == null) mandateRepo.save(mandate, event) else mandateRepo.update(mandate, event)
    }

    override suspend fun revokeMandate(cmd: RevokeMandateCommand): PartyMandate {
        val mandate = mandateRepo.findById(cmd.mandateId)?.takeIf { it.principalPartyId == cmd.principalPartyId }
            ?: throw PartyNotFoundException(cmd.mandateId)
        if (mandate.status != MandateStatus.ACTIVE) return mandate
        val now = Instant.now(clock)
        val revoked = mandate.copy(
            status = MandateStatus.REVOKED,
            revokedAt = now,
            revokeReason = cmd.reason,
            updatedAt = now,
        )
        return mandateRepo.update(revoked, PartyEvents.mandateRevoked(revoked, now, PartyActor.system("party-api")))
    }

    override suspend fun listMandates(principalPartyId: UUID): List<PartyMandate> =
        mandateRepo.findByPrincipal(principalPartyId)

    override suspend fun actingFor(agentPartyId: UUID): List<ActingForProfile> {
        val now = Instant.now(clock)
        return mandateRepo.findByAgent(agentPartyId)
            .filter { it.isActiveAt(now) }
            .mapNotNull { m ->
                partyRepo.findById(m.principalPartyId)?.takeIf {
                    it.status != PartyStatus.CLOSED &&
                        it.status != PartyStatus.MERGED
                }?.let { ActingForProfile(it, m) }
            }
    }

    override suspend fun updateParty(cmd: UpdatePartyCommand): Party {
        val party = partyRepo.findById(cmd.id) ?: throw PartyNotFoundException(cmd.id)
        val updated = party.copy(
            email = cmd.email ?: party.email,
            phone = cmd.phone ?: party.phone,
            address = cmd.address ?: party.address,
            tradingName = cmd.tradingName ?: party.tradingName,
            legalName = cmd.legalName ?: party.legalName,
            dateOfBirth = cmd.dateOfBirth ?: party.dateOfBirth,
            nationality = cmd.nationality ?: party.nationality,
            updatedAt = Instant.now(clock),
        )
        // ADR-0256 D1 / #4458: the publisher declares materiality, computed from this diff. The
        // event is published either way — account-service reconciles on PARTY_UPDATED regardless
        // — but only MATERIAL is a KYC re-screening trigger, and NO_CHANGE is its own outcome.
        changeMetrics.changeClassified(PartyChange.classify(party, updated).materiality)
        val saved = partyRepo.update(
            updated,
            PartyEvents.updated(party, updated, Instant.now(clock), PartyActor.system("party-api")),
        )
        return saved
    }

    override suspend fun addDocument(cmd: AddDocumentCommand): PartyDocument {
        partyRepo.findById(cmd.partyId) ?: throw PartyNotFoundException(cmd.partyId)
        val doc = PartyDocument(
            id = UUID.randomUUID(),
            partyId = cmd.partyId,
            documentType = cmd.documentType,
            documentNumber = cmd.documentNumber,
            issuingCountry = cmd.issuingCountry,
            expiryDate = cmd.expiryDate,
            verifiedAt = null,
            createdAt = Instant.now(clock),
        )
        return documentRepo.save(doc)
    }

    override suspend fun listDocuments(partyId: UUID): List<PartyDocument> {
        partyRepo.findById(partyId) ?: throw PartyNotFoundException(partyId)
        return documentRepo.findByPartyId(partyId)
    }

    override suspend fun exportPartyData(id: UUID): PartyGdprExport {
        val party = partyRepo.findById(id) ?: throw PartyNotFoundException(id)
        val documents = documentRepo.findByPartyId(id)
        val kycData = gdprAggregation.fetchKycData(id)
        val cardData = gdprAggregation.fetchCardData(id)
        return PartyGdprExport(party, documents, clock.instant(), kycData, cardData)
    }

    override suspend fun exportPartyPortabilityData(id: UUID): PartyPortabilityExport {
        val party = partyRepo.findById(id) ?: throw PartyNotFoundException(id)
        val documents = documentRepo.findByPartyId(id)
        // ADR-0204 D1: no kyc-service call at all — its data is Art. 6(1)(c) legal obligation,
        // and the basis filter is only honest if it is structural (nothing to forget to filter).
        val accounts = portabilityAggregation.fetchAccountsWithTransactions(id)
        val cards = portabilityAggregation.fetchCards(id)
        return PartyPortabilityExport(party, documents, accounts, cards, clock.instant())
    }

    override suspend fun listParties(page: Int, size: Int, status: PartyStatus?): Map<String, Any> {
        val items = if (status != null) partyRepo.listByStatus(status, page, size) else partyRepo.listAll(page, size)
        val total = if (status != null) partyRepo.countByStatus(status) else partyRepo.countAll()
        val result = mutableMapOf<String, Any>(
            "items" to items.map { p ->
                mapOf(
                    "id" to p.id,
                    "partyType" to p.partyType,
                    "status" to p.status,
                    "legalName" to p.legalName,
                    "tradingName" to p.tradingName,
                    "email" to p.email,
                    "kycStatus" to p.kycStatus,
                    "createdAt" to p.createdAt,
                )
            },
            "total" to total,
            "page" to page,
            "size" to size,
        )
        // Present the applied filter so callers don't have to echo it back
        status?.name?.let { result["statusFilter"] = it }
        return result
    }

    // ADR-0055 bounded search, extended to business keys by ADR-0228 D1 (name, email, phone,
    // tax id, registration number — never birth number). The shared SearchRequest contract owns
    // the DB-safety guardrails (page-size clamp, min-term length, LIKE-escaping); this service
    // owns the SQL and the keyset cursor. A blank/`*`/sub-2-char term has no fulltext predicate —
    // we return an empty page rather than enumerate the whole table from the /search surface
    // (use GET /parties to list). Data-minimised response via toSimpleResponse.
    override suspend fun searchParties(query: SearchPartiesQuery): CursorPage<Party> {
        val req = SearchRequest.of(query.q, query.limit, query.cursor)
        if (!req.hasTerm) {
            return CursorPage(data = emptyList(), pagination = PageInfo(limit = req.limit, hasNextPage = false))
        }
        val afterId = req.cursor?.let { runCatching { UUID.fromString(CursorEncoder.decode(it)) }.getOrNull() }
        // Fetch limit+1 to detect a next page without a second count query.
        val rows = partyRepo.searchByBusinessKeys(req.term!!, req.limit + 1, afterId)
        val hasNext = rows.size > req.limit
        val pageRows = if (hasNext) rows.dropLast(1) else rows
        val nextCursor = if (hasNext &&
            pageRows.isNotEmpty()
        ) {
            CursorEncoder.encode(pageRows.last().id.toString())
        } else {
            null
        }
        return CursorPage(
            data = pageRows,
            pagination = PageInfo(limit = req.limit, hasNextPage = hasNext, nextCursor = nextCursor),
        )
    }

    override suspend fun updateKycStatus(partyId: UUID, status: KycStatus): Party {
        val party = partyRepo.findById(partyId) ?: throw PartyNotFoundException(partyId)
        val updated = party.copy(
            kycStatus = status,
            status = deriveStatus(status, party.amlStatus, party.status),
            updatedAt = Instant.now(clock),
        )
        val saved = partyRepo.update(
            updated,
            PartyEvents.kycStatusChanged(updated, Instant.now(clock), PartyActor.system("kyc-status-projection")),
        )
        countIfVerifyingTransition(party.status, saved)
        return saved
    }

    override suspend fun updateAmlStatus(partyId: UUID, amlStatus: AmlStatus): Party {
        val party = partyRepo.findById(partyId) ?: throw PartyNotFoundException(partyId)
        val updated = party.copy(
            amlStatus = amlStatus,
            status = deriveStatus(party.kycStatus, amlStatus, party.status),
            updatedAt = Instant.now(clock),
        )
        // Emits the party's current status (incl. ACTIVE) to downstream consumers
        // (account-service activation, onboarding cockpit) on the party events topic.
        val saved = partyRepo.update(
            updated,
            PartyEvents.kycStatusChanged(updated, Instant.now(clock), PartyActor.system("aml-status-projection")),
        )
        countIfVerifyingTransition(party.status, saved)
        return saved
    }

    /**
     * Counts `openbank.parties.verified` once, on the transition INTO the verified (ACTIVE) state
     * (ADR-0077 §metric catalogue). ACTIVE is the two-key terminal of [deriveStatus] (KYC APPROVED + AML
     * CLEARED). Guards on the edge `previous != ACTIVE && current == ACTIVE` so it fires exactly
     * once on the verifying step — never on a rejection (→ SUSPENDED) and never on a status update
     * that leaves an already-ACTIVE party ACTIVE.
     */
    private fun countIfVerifyingTransition(previousStatus: PartyStatus, saved: Party) {
        if (previousStatus != PartyStatus.ACTIVE && saved.status == PartyStatus.ACTIVE) {
            metrics.partyVerified(saved.partyType.name)
        }
    }

    /**
     * Two-key activation gate: a party becomes ACTIVE only when KYC is APPROVED
     * AND AML is CLEARED. Any hard negative (KYC REJECTED or AML BLOCKED) suspends.
     * No ADR records this conjunction — ADR-0069 defines the ACTIVE gate on the onboarding
     * journey (KYC only) and ADR-0032 owns the `AmlCase` CLEARED/BLOCKED terminals; the
     * two-key rule itself lives here in code.
     * Fail-closed: absent either positive signal the party stays PENDING_KYC; a CLOSED party
     * is never re-opened.
     */
    private fun deriveStatus(kyc: KycStatus, aml: AmlStatus, current: PartyStatus): PartyStatus = when {
        current == PartyStatus.CLOSED -> PartyStatus.CLOSED
        // ADR-0179: MERGED is terminal for the same reason CLOSED is — a late KYC or AML callback
        // on a retired duplicate must not resurrect it into ACTIVE.
        current == PartyStatus.MERGED -> PartyStatus.MERGED
        // EXPIRED is set by AbandonedRegistrationCleaner's daily sweep, which explicitly expects
        // this to suspend the party ("system expiry... party -> SUSPENDED") — without it here,
        // an abandoned registration silently reverted to PENDING_KYC instead.
        kyc == KycStatus.REJECTED || kyc == KycStatus.EXPIRED || aml == AmlStatus.BLOCKED -> PartyStatus.SUSPENDED
        kyc == KycStatus.APPROVED && aml == AmlStatus.CLEARED -> PartyStatus.ACTIVE
        else -> PartyStatus.PENDING_KYC
    }

    override suspend fun eraseParty(cmd: ErasePartyCommand) {
        partyRepo.findById(cmd.id) ?: throw PartyNotFoundException(cmd.id)
        // GDPR Art. 17: delete binary document files before anonymizing the party row.
        // Order matters: files first (they carry biometric PII), then anonymize identity.
        documentFileRepo.deleteByPartyId(cmd.id)
        partyRepo.anonymize(cmd.id, PartyEvents.erased(cmd.id, Instant.now(clock)))
    }

    /**
     * ADR-0179: retire [cmd.id] as a duplicate of [cmd.mergedIntoPartyId].
     *
     * Every precondition is fail-closed, and the account guard deliberately lets its exception
     * propagate: if we cannot establish that the retired party owns no open account, we must not
     * merge. Account closure does not check the balance (ADR-0109 option B), so a funded account
     * on a retired identity would strand silently with nothing downstream to catch it.
     */
    override suspend fun mergeParty(cmd: MergePartyCommand): Party {
        if (cmd.id == cmd.mergedIntoPartyId) {
            throw PartyMergeRejectedException("A party cannot be merged into itself: ${cmd.id}")
        }
        val source = partyRepo.findById(cmd.id) ?: throw PartyNotFoundException(cmd.id)
        val target = partyRepo.findById(cmd.mergedIntoPartyId)
            ?: throw PartyNotFoundException(cmd.mergedIntoPartyId)

        if (source.status == PartyStatus.MERGED) {
            throw PartyMergeRejectedException(
                "Party ${source.id} is already merged into ${source.mergedIntoPartyId}",
            )
        }
        if (source.status == PartyStatus.CLOSED) {
            throw PartyMergeRejectedException("Party ${source.id} is erased (CLOSED) and cannot be merged")
        }
        // No chains: a survivor that is itself retired would leave consumers following two hops,
        // and every consumer would have to implement the same loop. The caller resolves first.
        if (target.status == PartyStatus.MERGED) {
            throw PartyMergeRejectedException(
                "Target ${target.id} is itself merged into ${target.mergedIntoPartyId}; " +
                    "merge into the surviving party instead",
            )
        }
        if (target.status == PartyStatus.CLOSED) {
            throw PartyMergeRejectedException("Target ${target.id} is erased (CLOSED) and cannot receive a merge")
        }

        val openAccounts = accountGuard.findOpenAccounts(source.id)
        if (openAccounts.isNotEmpty()) {
            throw PartyMergeRejectedException(
                "Party ${source.id} still owns ${openAccounts.size} open account(s): " +
                    "${openAccounts.joinToString()}. Sweep the balances and close them first.",
            )
        }

        val merged = source.copy(
            status = PartyStatus.MERGED,
            mergedIntoPartyId = target.id,
            updatedAt = Instant.now(clock),
        )
        val saved = partyRepo.update(
            merged,
            PartyEvents.merged(merged, target.id, Instant.now(clock), PartyActor.system("party-merge")),
        )
        return saved
    }

    override suspend fun selfRegisterParty(cmd: SelfRegisterPartyCommand): Pair<Party, Boolean> {
        // Idempotent: return existing party for this Keycloak sub
        partyRepo.findByKeycloakSub(cmd.keycloakSub)?.let { return Pair(it, false) }
        val party = Party(
            id = UUID.randomUUID(),
            partyType = cmd.partyType,
            status = PartyStatus.PENDING_KYC,
            legalName = cmd.legalName,
            tradingName = null,
            dateOfBirth = cmd.dateOfBirth,
            nationality = cmd.nationality,
            taxId = null,
            registrationNumber = null,
            email = cmd.email,
            phone = cmd.phone,
            address = cmd.address,
            kycStatus = KycStatus.NOT_STARTED,
            keycloakSub = cmd.keycloakSub,
            createdAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        )
        val saved = partyRepo.save(
            party,
            PartyEvents.created(party, Instant.now(clock), PartyActor.customer(cmd.keycloakSub)),
        )
        return Pair(saved, true)
    }

    override suspend fun getMyParty(keycloakSub: String): Party? = partyRepo.findByKeycloakSub(keycloakSub)

    override suspend fun uploadDocument(cmd: UploadDocumentCommand): PartyDocumentFile {
        partyRepo.findById(cmd.partyId) ?: throw PartyNotFoundException(cmd.partyId)
        val file = PartyDocumentFile(
            id = UUID.randomUUID(),
            partyId = cmd.partyId,
            documentType = cmd.documentType,
            fileName = cmd.fileName,
            mimeType = cmd.mimeType,
            content = cmd.content,
            uploadedAt = Instant.now(clock),
        )
        return documentFileRepo.save(file)
    }

    override suspend fun getDocumentContent(partyId: UUID, fileId: UUID): PartyDocumentFile? =
        documentFileRepo.findByIdAndPartyId(fileId, partyId)

    override suspend fun getPartyKeycloakSub(id: UUID): String? = partyRepo.findById(id)?.keycloakSub

    override suspend fun listPayees(partyId: UUID): List<Payee> = payeeRepo.findByPartyId(partyId)

    override suspend fun savePayee(cmd: SavePayeeCommand): Payee {
        val normalizedIban = cmd.iban.filterNot { it.isWhitespace() }.uppercase()
        val existing = payeeRepo.findByPartyId(cmd.partyId)
        val alreadySaved = existing.any { it.iban == normalizedIban }
        if (!alreadySaved && existing.size >= MAX_PAYEES) {
            throw PayeeLimitExceededException(cmd.partyId)
        }
        val payee = Payee(
            id = existing.firstOrNull { it.iban == normalizedIban }?.id ?: Ids.newId(),
            partyId = cmd.partyId,
            name = cmd.name.trim(),
            iban = normalizedIban,
            bic = cmd.bic?.trim()?.ifBlank { null },
            createdAt = Instant.now(clock),
        )
        return payeeRepo.save(payee)
    }

    override suspend fun deletePayee(partyId: UUID, iban: String) {
        payeeRepo.deleteByPartyIdAndIban(partyId, iban.filterNot { it.isWhitespace() }.uppercase())
    }
}

/** Mirrors the app's own PayeeStore.MAX_PAYEES. */
private const val MAX_PAYEES = 30

/** SHA-256 rendered as lowercase hex. */
private const val SHA256_HEX_LENGTH = 64

/** Upper bound on hashes accepted in one directory lookup — a request-shape guard, not a privacy one. */
private const val MAX_DIRECTORY_LOOKUP = 500
