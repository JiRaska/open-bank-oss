// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.customeredge.infrastructure.cnb

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

data class BankDto(val code: String, val name: String, val bic: String? = null)

/**
 * In-process cache of Czech bank codes.
 *
 * Data source:  CNB JERR XML at [cnbBanksUrl] (configurable via CNB_BANKS_URL env var).
 * Fallback:     embedded /banks.json is loaded on startup and used when CNB is unreachable.
 * Refresh:      background thread fetches from CNB on startup + every 24 hours.
 * Thread-safety: [cache] is an AtomicReference; all updates are done via set().
 */
@ApplicationScoped
class CnbBanksClient {

    companion object {
        private val LOG = Logger.getLogger(CnbBanksClient::class.java.name)
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val FETCH_TIMEOUT_SECONDS = 15L
        private const val REFRESH_PERIOD_HOURS = 24L
        private const val HTTP_OK = 200
        private const val BANK_CODE_LENGTH = 4
    }

    @ConfigProperty(name = "openbank.edge.cnb-banks-url")
    lateinit var cnbBanksUrl: String

    @Inject
    lateinit var objectMapper: ObjectMapper

    private val cache = AtomicReference<List<BankDto>>(emptyList())

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cnb-banks-refresh").also { it.isDaemon = true }
    }

    fun onStart(@Observes @Suppress("UNUSED_PARAMETER") e: StartupEvent) {
        loadEmbedded()
        scheduler.scheduleAtFixedRate(::refreshFromCnb, 0L, REFRESH_PERIOD_HOURS, TimeUnit.HOURS)
    }

    fun getBanks(): List<BankDto> = cache.get()

    // --- private -----------------------------------------------------------------

    private fun loadEmbedded() {
        val stream = CnbBanksClient::class.java.getResourceAsStream("/banks.json") ?: run {
            LOG.warning("CNB banks: /banks.json not found on classpath")
            return
        }
        try {
            val banks: List<BankDto> = objectMapper.readValue(stream, object : TypeReference<List<BankDto>>() {})
            if (banks.isNotEmpty()) cache.set(banks)
            LOG.info("CNB banks: seeded ${banks.size} banks from embedded JSON")
        } catch (ex: IOException) {
            LOG.warning("CNB banks: could not parse embedded JSON — ${ex.message}")
        }
    }

    @Suppress("TooGenericExceptionCaught") // CNB XML fetch can fail in many ways; log and keep cache
    internal fun refreshFromCnb() {
        try {
            val req = HttpRequest.newBuilder(URI.create(cnbBanksUrl))
                .GET()
                .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                .header("Accept", "application/xml, text/xml, */*")
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() != HTTP_OK) {
                LOG.warning("CNB banks: HTTP ${resp.statusCode()} — keeping current cache")
                return
            }
            val contentType = resp.headers().firstValue("Content-Type").orElse("")
            val banks = if ("json" in contentType) parseCnbJson(resp.body()) else parseCnbXml(resp.body())
            if (banks.isNotEmpty()) {
                cache.set(banks.sortedBy { it.code })
                LOG.info("CNB banks: refreshed ${banks.size} banks from $cnbBanksUrl")
            } else {
                LOG.warning("CNB banks: parsed 0 entries — keeping current cache")
            }
        } catch (ex: Exception) {
            LOG.warning("CNB banks: refresh failed (${ex.message}) — using current cache")
        }
    }

    /** Parse CNB JERR-style XML: <Banky><Banka><Kod>…</Kod><Nazev>…</Nazev><BIC>…</BIC><Stav>A</Stav> */
    @Suppress("TooGenericExceptionCaught") // XML parser throws SAXException + IOException + more
    private fun parseCnbXml(input: InputStream): List<BankDto> = try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        val nodes = doc.getElementsByTagName("Banka")
        (0 until nodes.length)
            .mapNotNull { i -> parseBankaElement(nodes.item(i) as? Element ?: return@mapNotNull null) }
            .sortedBy { it.code }
    } catch (ex: Exception) {
        LOG.warning("CNB banks: XML parse error (${ex.message}) — returning empty list")
        emptyList()
    }

    private fun parseBankaElement(el: Element): BankDto? {
        val code = el.text("Kod") ?: return null
        val name = el.text("Nazev") ?: return null
        val bic = el.text("BIC")?.takeIf { it.isNotBlank() }
        val stav = el.text("Stav")
        if (stav != null && stav != "A") return null
        return BankDto(code = code.trim().padStart(BANK_CODE_LENGTH, '0'), name = name.trim(), bic = bic)
    }

    private fun parseCnbJson(input: InputStream): List<BankDto> =
        objectMapper.readValue(input, object : TypeReference<List<BankDto>>() {})

    private fun Element.text(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}
