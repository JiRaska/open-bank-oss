// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.customeredge.infrastructure.cnb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.io.IOException
import java.time.LocalDate
import java.util.logging.Logger

data class BankDto(val code: String, val name: String, val bic: String? = null)

/**
 * The Czech bank-code list, served from a **committed snapshot** — `/banks.json`.
 *
 * ## Why this no longer fetches anything (issue #2918)
 *
 * It used to GET ČNB's JERR registry at `apl.cnb.cz/apljerrpp/jerr_banky.xml` every 24 h and fall
 * back to the embedded file when that was "unreachable". That URL has been a **404 for the whole
 * life of the service**, so the fallback was not a fallback: it was the only code path that ever
 * ran, and the fetch existed solely to make the list look live. A failing refresh is invisible by
 * construction here — it logs a warning and keeps serving, which is indistinguishable from success.
 *
 * There is no replacement URL. ČNB moved JERRS's machine-readable interface to a SOAP service
 * (`aplc.cnb.cz/jerrsws/ws`, WSDL published) whose endpoint **refuses anonymous TLS at the host
 * level** — it is registered-access, gated behind an application form, and would need a client
 * certificate plus a SOAP/XSD client. The payment-system pages publish no static bank-code file
 * either. Measurements are on #2918.
 *
 * So the snapshot is now the declared source of truth rather than an accident. That is a smaller
 * claim than "live registry data", and it is a true one — which is the entire point of the change.
 *
 * ## What that costs, stated plainly
 *
 * A bank code registered after [snapshotDate] is unknown to this service. The list moves on the
 * order of once or twice a year, so the exposure is small but not zero, and refreshing it is a
 * deliberate human act: edit `/banks.json`, bump `snapshotDate`, open a PR.
 * `BankRegistrySnapshotTest` fails if the file is missing, empty, malformed, or undated, so the
 * registry can never silently degrade to the empty list the old code would happily serve.
 */
@ApplicationScoped
class CnbBanksClient {

    companion object {
        private val LOG = Logger.getLogger(CnbBanksClient::class.java.name)
        internal const val RESOURCE = "/banks.json"
    }

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** The committed snapshot, ordered by bank code. Never empty — startup fails first. */
    lateinit var banks: List<BankDto>
        private set

    /** The day the committed list was taken from ČNB. */
    lateinit var snapshotDate: LocalDate
        private set

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") e: StartupEvent) = load()

    fun getBanks(): List<BankDto> = banks

    /**
     * Loads the snapshot, and **throws** if it cannot. Deliberate: the file is now the only source,
     * so a parse failure is a broken deployment, not a degraded one. The old code logged a warning
     * and left the cache empty, which surfaced as `GET /banks` returning `[]` — a valid, successful
     * and entirely wrong response.
     */
    internal fun load() {
        val stream = CnbBanksClient::class.java.getResourceAsStream(RESOURCE)
            ?: error("Bank registry snapshot $RESOURCE is not on the classpath")
        val snapshot = try {
            objectMapper.readValue(stream, BankSnapshot::class.java)
        } catch (ex: IOException) {
            throw IllegalStateException("Bank registry snapshot $RESOURCE is not parseable", ex)
        }
        check(snapshot.banks.isNotEmpty()) { "Bank registry snapshot $RESOURCE contains no banks" }
        banks = snapshot.banks.sortedBy { it.code }
        snapshotDate = LocalDate.parse(snapshot.snapshotDate)
        LOG.info(
            "Bank registry: ${banks.size} banks from the committed snapshot of $snapshotDate " +
                "(source: ${snapshot.source}). Not fetched — see #2918.",
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class BankSnapshot(
        val snapshotDate: String,
        val source: String,
        val banks: List<BankDto>,
    )
}
