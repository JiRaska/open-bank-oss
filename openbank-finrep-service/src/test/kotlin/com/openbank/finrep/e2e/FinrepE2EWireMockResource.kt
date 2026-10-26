// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager

/**
 * Stands in for every out-of-process hop finrep makes — the ledger trial-balance read (shared
 * `openbank-services` identity), the risk-engine snapshot read (finrep's OWN named `m2m` identity,
 * ADR-0313 D6) and the Keycloak token endpoint both clients mint from. Same shape as vop's,
 * statement's, kyb's and risk-engine's downstream resources: the REST clients, their OIDC filters
 * and the count-and-rethrow failure paths all cross a wire they actually have to.
 *
 * The token endpoint returns a DIFFERENT bearer per asking client (#10486 pinned on the wire):
 * the ledger read must ride `Bearer finrep-e2e-default-token`, the risk-engine read
 * `Bearer finrep-e2e-m2m-token` — a shared-principal regression fails here, not in a log.
 *
 * The request journal is the fan-out proof: [requestsTo] counts requests since the last reset, so
 * a test can assert "FROZEN evidence hit the frozen path and never the live one", "the untied run
 * was listed but never fetched", or "validation refused before any downstream was asked" with
 * [com.github.tomakehurst.wiremock.verification.LoggedRequest] bodies as the verbatim evidence.
 *
 * Static stubs cover one fixture per (endpoint, reporting date). The YTD wrong-window fixture is
 * registered per test ([stubYtdWrongWindow]) because no static stub should answer a deliberately
 * malformed lineage response.
 */
class FinrepE2EWireMockResource : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val wm = WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        server = wm
        wm.start()
        stubTokenEndpoint(wm)
        stubLedger(wm)
        stubRiskEngine(wm)

        val base = wm.baseUrl()
        return mapOf(
            // Build-time flags read at augmentation: the resource config (high ordinal, applied
            // before the app boots) is what makes the reactive OIDC filter build step and the
            // runtime token producers active — the %test yaml alone is not consulted there.
            "quarkus.oidc-client.enabled" to "true",
            "quarkus.oidc-client.auth-server-url" to base,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to TOKEN_PATH,
            "quarkus.oidc-client.client-id" to "openbank-services",
            "quarkus.oidc-client.credentials.secret" to "test-secret",
            "quarkus.oidc-client.grant.type" to "client",
            // ADR-0313 D6: the risk engine grants risk.snapshot.read to finrep's OWN identity —
            // the named `m2m` client, kept apart from the shared default above.
            "quarkus.oidc-client.m2m.auth-server-url" to base,
            "quarkus.oidc-client.m2m.discovery-enabled" to "false",
            "quarkus.oidc-client.m2m.token-path" to TOKEN_PATH,
            "quarkus.oidc-client.m2m.client-id" to "openbank-finrep",
            "quarkus.oidc-client.m2m.credentials.secret" to "test-secret",
            "quarkus.oidc-client.m2m.grant.type" to "client",
            "quarkus.rest-client.ledger-service.url" to base,
            "quarkus.rest-client.risk-engine.url" to base,
        )
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    /** Keycloak token endpoint, one answer per asking client. */
    private fun stubTokenEndpoint(wm: WireMockServer) {
        // Quarkus' oidc-client may authenticate with client_id in the form body OR with HTTP
        // Basic (client_id:secret), so both carriers are stubbed; the risk-engine client must
        // ride the `m2m` client (openbank-finrep), the ledger client the shared default
        // (openbank-services) — #10486 pinned on the wire.
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(1).withRequestBody(containing("client_id=openbank-finrep"))
                .willReturn(
                    okJson("""{"access_token":"finrep-e2e-m2m-token","token_type":"Bearer","expires_in":300}"""),
                ),
        )
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).atPriority(1)
                .withHeader("Authorization", equalTo("Basic b3BlbmJhbmstZmlucmVwOnRlc3Qtc2VjcmV0"))
                .willReturn(
                    okJson("""{"access_token":"finrep-e2e-m2m-token","token_type":"Bearer","expires_in":300}"""),
                ),
        )
        wm.stubFor(
            post(urlEqualTo(TOKEN_PATH)).willReturn(
                okJson("""{"access_token":"finrep-e2e-default-token","token_type":"Bearer","expires_in":300}"""),
            ),
        )
    }

    /** Ledger trial-balance reads: one fixture per reporting date, frozen paths and the live one. */
    private fun stubLedger(wm: WireMockServer) {
        // F01.01 / COREP C_01.00 frozen closing balance at 2026-06-30: assets 500 000 =
        // liabilities 300 000 + retained result 200 000, ledger agrees it ties (sign convention:
        // net = debit − credit, so credit-normal accounts are NEGATIVE).
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-06-30/frozen-closing-balance")).willReturn(okJson(BALANCED_JUNE)),
        )
        // The live path serves the same body so a LIVE_PREVIEW render succeeds — the journal then
        // discriminates which path the evidence mode actually dialled.
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-06-30/closing-balance")).willReturn(okJson(BALANCED_JUNE)),
        )
        // F02.00 frozen year-to-date at the same date: six months of frozen lineage, one 64-hex
        // content hash per source period (the adapter re-checks the whole window).
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-06-30/frozen-year-to-date-trial-balance"))
                .willReturn(okJson(YTD_JUNE)),
        )
        // 2026-07-31: the ledger's own verdict says it does NOT tie (an extra unbalanced asset) —
        // the preflight blocker case.
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-07-31/frozen-closing-balance")).willReturn(okJson(UNBALANCED_JULY)),
        )
        // 2026-08-31: full capital-structure accounts (6000-6060 EQUITY) so C_01.00 derives real
        // own-funds subtotals instead of flagged gaps.
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-08-31/frozen-closing-balance")).willReturn(okJson(CAPITAL_AUGUST)),
        )
        // 2026-10-31: ledger outage — the count-and-rethrow must surface this as a failure with
        // the downstream status preserved (503, not the last-resort 500).
        wm.stubFor(
            get(urlEqualTo("$LEDGER/MONTH/2026-10-31/frozen-closing-balance"))
                .willReturn(aResponse().withStatus(503)),
        )
        // The closed-periods list: only the two MONTH+FROZEN+LINES_V1 periods are safe evidence;
        // the DRAFT month, the YEAR period and the legacy HASH_ONLY month must be filtered out.
        wm.stubFor(
            get(urlEqualTo("$LEDGER?from=1970-01-01&to=9999-12-31")).willReturn(okJson(PERIODS)),
        )
    }

    /** Risk-engine snapshot reads: a newer UNTIED run must never be picked over the TIED_OUT one. */
    private fun stubRiskEngine(wm: WireMockServer) {
        wm.stubFor(
            get(urlEqualTo("$RISK?limit=100")).willReturn(okJson(RUNS)),
        )
        wm.stubFor(
            get(urlEqualTo("$RISK/run-tied-jun/capital")).willReturn(okJson(CAPITAL)),
        )
        wm.stubFor(
            get(urlEqualTo("$RISK/run-tied-jun/liquidity")).willReturn(okJson(LIQUIDITY)),
        )
    }

    companion object {
        private const val TOKEN_PATH = "/realms/openbank/protocol/openid-connect/token"
        private const val LEDGER = "/api/v1/ledger/periods"
        private const val RISK = "/api/v1/risk/snapshots"

        private const val BALANCED_JUNE = """{
            "period": "MONTH:2026-06",
            "balanced": true,
            "lines": [
                {"code": "1000", "type": "ASSET", "net": 500000, "currency": "CZK"},
                {"code": "2000", "type": "LIABILITY", "net": -300000, "currency": "CZK"},
                {"code": "4100", "type": "INCOME", "net": -260000, "currency": "CZK"},
                {"code": "5100", "type": "EXPENSE", "net": 60000, "currency": "CZK"}
            ]
        }"""

        private val YTD_JUNE = """{
            "period": "MONTH:2026-06",
            "balanced": true,
            "from": "2026-01-01",
            "to": "2026-06-30",
            "sourcePeriods": [
                "MONTH:2026-01", "MONTH:2026-02", "MONTH:2026-03",
                "MONTH:2026-04", "MONTH:2026-05", "MONTH:2026-06"
            ],
            "sourceContentHashes": [
                "${"1".repeat(64)}", "${"2".repeat(64)}", "${"3".repeat(64)}",
                "${"4".repeat(64)}", "${"5".repeat(64)}", "${"6".repeat(64)}"
            ],
            "lines": [
                {"code": "1000", "type": "ASSET", "net": 460000, "currency": "CZK"},
                {"code": "2000", "type": "LIABILITY", "net": -420000, "currency": "CZK"},
                {"code": "4100", "type": "INCOME", "net": -120000, "currency": "CZK"},
                {"code": "5100", "type": "EXPENSE", "net": 80000, "currency": "CZK"}
            ]
        }"""

        private const val UNBALANCED_JULY = """{
            "period": "MONTH:2026-07",
            "balanced": false,
            "lines": [
                {"code": "1000", "type": "ASSET", "net": 500000, "currency": "CZK"},
                {"code": "1001", "type": "ASSET", "net": 70000, "currency": "CZK"},
                {"code": "2000", "type": "LIABILITY", "net": -300000, "currency": "CZK"},
                {"code": "4100", "type": "INCOME", "net": -260000, "currency": "CZK"},
                {"code": "5100", "type": "EXPENSE", "net": 60000, "currency": "CZK"}
            ]
        }"""

        private const val CAPITAL_AUGUST = """{
            "period": "MONTH:2026-08",
            "balanced": true,
            "lines": [
                {"code": "6000", "type": "EQUITY", "net": -10000000, "currency": "CZK"},
                {"code": "6010", "type": "EQUITY", "net": -2000000, "currency": "CZK"},
                {"code": "6020", "type": "EQUITY", "net": -500000, "currency": "CZK"},
                {"code": "6030", "type": "EQUITY", "net": -1000000, "currency": "CZK"},
                {"code": "6040", "type": "EQUITY", "net": 250000, "currency": "CZK"},
                {"code": "6050", "type": "EQUITY", "net": -1000000, "currency": "CZK"},
                {"code": "6060", "type": "EQUITY", "net": -2000000, "currency": "CZK"}
            ]
        }"""

        private const val PERIODS = """[
            {"periodType": "MONTH", "to": "2026-06-30", "status": "FROZEN", "evidenceState": "LINES_V1"},
            {"periodType": "MONTH", "to": "2026-05-31", "status": "FROZEN", "evidenceState": "LINES_V1"},
            {"periodType": "MONTH", "to": "2026-07-31", "status": "DRAFT", "evidenceState": "LINES_V1"},
            {"periodType": "YEAR", "to": "2026-12-31", "status": "FROZEN", "evidenceState": "LINES_V1"},
            {"periodType": "MONTH", "to": "2026-04-30", "status": "FROZEN", "evidenceState": "HASH_ONLY"}
        ]"""

        // run-tied-jun and run-tied-may are TIED_OUT; run-untied-jun is NEWER (recordedAt later)
        // but UNTIED, and run-tied-may is tied at a different date — the adapter must pick
        // run-tied-jun for a 2026-06-30 report and nothing for 2026-09-30.
        private const val RUNS = """{
            "runs": [
                {"id": "run-tied-jun", "asOf": "2026-06-30", "recordedAt": "2026-07-01T10:00:00Z", "status": "TIED_OUT"},
                {"id": "run-untied-jun", "asOf": "2026-06-30", "recordedAt": "2026-07-02T10:00:00Z", "status": "UNTIED"},
                {"id": "run-tied-may", "asOf": "2026-05-31", "recordedAt": "2026-06-01T09:00:00Z", "status": "TIED_OUT"},
                {"id": "run-untied-sep", "asOf": "2026-09-30", "recordedAt": "2026-10-01T10:00:00Z", "status": "UNTIED"}
            ]
        }"""

        private const val CAPITAL = """{
            "runId": "run-tied-jun",
            "asOf": "2026-06-30",
            "parameterSetId": "eu-crr3-sa",
            "parameterSetVersion": "1",
            "provenance": "synthetic",
            "currencies": [
                {"currency": "CZK", "classes": [], "totalRwa": 6095896.08}
            ],
            "total": {
                "currency": "CZK",
                "classes": [
                    {"exposureClass": "sovereign-and-central-bank", "ead": 10000000.00, "rwa": 0.00},
                    {"exposureClass": "bank", "ead": 1500.00, "rwa": 2250.00},
                    {"exposureClass": "retail", "ead": 6093596.08, "rwa": 6093596.08},
                    {"exposureClass": "cash", "ead": 300.00, "rwa": 0.00},
                    {"exposureClass": "other-asset", "ead": 50.00, "rwa": 50.00}
                ],
                "totalRwa": 6095896.08
            },
            "unclassified": [],
            "totalNotStated": null
        }"""

        private const val LIQUIDITY = """{
            "runId": "run-tied-jun",
            "asOf": "2026-06-30",
            "parameterSetId": "eu-2015-61-crr2",
            "parameterSetVersion": "2",
            "provenance": "synthetic",
            "currencies": [
                {"currency": "CZK", "lcr": {"hqla": {"lines": [], "level1": 1250, "level2a": 170, "level2b": 105}}}
            ],
            "total": {
                "currency": "CZK",
                "lcr": {
                    "hqla": {
                        "lines": [
                            {"level": "L1", "marketValue": 1000, "haircut": 0, "afterHaircut": 1000},
                            {"level": "L1", "marketValue": 250, "haircut": 0, "afterHaircut": 250},
                            {"level": "L2A", "marketValue": 200, "haircut": 0.15, "afterHaircut": 170},
                            {"level": "L2B", "marketValue": 100, "haircut": 0.25, "afterHaircut": 75},
                            {"level": "L2B", "marketValue": 60, "haircut": 0.50, "afterHaircut": 30}
                        ],
                        "level1": 1250, "level2a": 170, "level2b": 105
                    },
                    "outflows": [], "totalOutflows": null,
                    "inflows": [], "totalInflows": null,
                    "inflowCap": null, "cappedInflows": null, "inflowCapBinding": null,
                    "netOutflows": null, "ratio": null
                }
            },
            "unclassified": [],
            "notes": [],
            "totalNotStated": null
        }"""

        private var server: WireMockServer? = null

        /** The started server, for per-case stubs and journal assertions. */
        fun wm(): WireMockServer = server ?: error("FinrepE2EWireMockResource not started")

        /**
         * Serves a deliberately malformed year-to-date answer for 2026-11-30: the period label and
         * window end are right but the window START is February — the adapter's
         * "different reporting window" check must refuse it. Registered per test so no static
         * stub ever serves a malformed lineage response.
         */
        fun stubYtdWrongWindow() {
            wm().stubFor(
                get(urlEqualTo("$LEDGER/MONTH/2026-11-30/frozen-year-to-date-trial-balance")).willReturn(
                    okJson(
                        """{
                            "period": "MONTH:2026-11",
                            "balanced": true,
                            "from": "2026-02-01",
                            "to": "2026-11-30",
                            "sourcePeriods": ["MONTH:2026-11"],
                            "sourceContentHashes": ["${"f".repeat(64)}"],
                            "lines": []
                        }""",
                    ),
                ),
            )
        }

        /** Journal size for a matcher — the fan-out / zero-fan-out proof. */
        fun requestsTo(requestPattern: RequestPatternBuilder): Int = wm().findAll(requestPattern).count()

        fun resetJournal() {
            wm().resetRequests()
        }
    }
}
