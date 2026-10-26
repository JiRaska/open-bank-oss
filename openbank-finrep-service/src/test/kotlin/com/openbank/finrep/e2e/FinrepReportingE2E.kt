// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.finrep.e2e

import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * E2E suite for finrep's two downstream reads — the ledger trial balance (shared
 * `openbank-services` identity) and the risk-engine snapshot (finrep's OWN named `m2m` identity,
 * ADR-0313 D6) — proving the REAL HTTP wiring: the actual REST clients, their OIDC filters, the
 * TIED_OUT run selection and the count-and-rethrow failure paths, all against
 * [FinrepE2EWireMockResource]. The module's unit tests mock the adapters, its pact tests pin the
 * request paths against a pact mock — neither boots Quarkus with the real clients over HTTP, and
 * the boot smoke test calls nothing downstream. The WireMock request journal is the evidence here:
 * which path the evidence mode dialled, which identity rode the wire, and which downstream was
 * never asked.
 */
@QuarkusTest
@TestProfile(FinrepE2EProfile::class)
class FinrepReportingE2E {

    @BeforeEach
    fun resetJournal() {
        FinrepE2EWireMockResource.resetJournal()
    }

    private fun ledgerHits(path: String) = FinrepE2EWireMockResource.wm()
        .findAll(getRequestedFor(urlEqualTo("/api/v1/ledger/periods$path")))

    private fun riskHits(path: String) = FinrepE2EWireMockResource.wm()
        .findAll(getRequestedFor(urlEqualTo("/api/v1/risk/snapshots$path")))

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `F01_01 FROZEN renders the frozen closing balance over the real ledger client`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01?asOf=2026-06-30&evidence=FROZEN")
        } Then {
            statusCode(200)
            body("templateId", equalTo("F01.01"))
            body("period", equalTo("2026-06-30"))
            body("isBalanced", equalTo(true))
            body("hasDataGaps", equalTo(false))
            body("cells.find { it.rowRef == 'r0380' }.value", equalTo(500000))
        }

        // The wire evidence: exactly one frozen-path read, carrying the SHARED client's bearer —
        // and the live path was never dialled for frozen evidence.
        val frozen = ledgerHits("/MONTH/2026-06-30/frozen-closing-balance")
        assertThat(frozen).hasSize(1)
        assertThat(frozen[0].getHeader("Authorization")).isEqualTo("Bearer finrep-e2e-default-token")
        assertThat(ledgerHits("/MONTH/2026-06-30/closing-balance")).isEmpty()
        assertThat(riskHits("")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `LIVE_PREVIEW rides the live closing-balance path with the same shared identity`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01?asOf=2026-06-30&evidence=LIVE_PREVIEW")
        } Then {
            statusCode(200)
            body("templateId", equalTo("F01.01"))
            body("isBalanced", equalTo(true))
        }

        val live = ledgerHits("/MONTH/2026-06-30/closing-balance")
        assertThat(live).hasSize(1)
        assertThat(live[0].getHeader("Authorization")).isEqualTo("Bearer finrep-e2e-default-token")
        assertThat(ledgerHits("/MONTH/2026-06-30/frozen-closing-balance")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `F02_00 FROZEN reads the year-to-date flow and honours the frozen lineage`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F02.00?asOf=2026-06-30&evidence=FROZEN")
        } Then {
            statusCode(200)
            body("templateId", equalTo("F02.00"))
            body("isBalanced", equalTo(true))
            body("hasDataGaps", equalTo(false))
            // Income 120 000 − expense 80 000 for the year.
            body("cells.find { it.rowRef == 'r0670' }.value", equalTo(40000))
        }

        assertThat(ledgerHits("/MONTH/2026-06-30/frozen-year-to-date-trial-balance")).hasSize(1)
        assertThat(ledgerHits("/MONTH/2026-06-30/frozen-closing-balance")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `F02_00 FROZEN refuses a non month-end date before any ledger call`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F02.00?asOf=2026-06-15&evidence=FROZEN")
        } Then {
            statusCode(400)
        }
        assertThat(ledgerHits("/MONTH/2026-06-15/frozen-year-to-date-trial-balance")).isEmpty()
        assertThat(ledgerHits("/MONTH/2026-06-15/frozen-closing-balance")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a year-to-date answer from a different reporting window is refused after crossing the wire`() {
        FinrepE2EWireMockResource.stubYtdWrongWindow()

        Given { this } When {
            get("/api/v1/finrep/templates/F02.00?asOf=2026-11-30&evidence=FROZEN")
        } Then {
            // The adapter's window check throws IllegalStateException, which no fleet mapper
            // classifies — the suspend bridge renders it 422 (measured), not a silent 200 and
            // not the last-resort 500.
            statusCode(422)
        }
        // The malformed answer was genuinely fetched and then rejected — a check that never fires
        // looks identical to one that never runs without this journal proof.
        assertThat(ledgerHits("/MONTH/2026-11-30/frozen-year-to-date-trial-balance")).hasSize(1)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `COREP C_01_00 derives own funds from the frozen closing balance`() {
        Given { this } When {
            get("/api/v1/corep/templates/C_01.00?asOf=2026-08-31")
        } Then {
            statusCode(200)
            body("templateId", equalTo("C_01.00"))
            body("hasDataGaps", equalTo(false))
            // Common Equity Tier 1 from the 6000-6060 capital accounts.
            body("cells.find { it.rowRef == 'r010' }.value", equalTo(16250000))
        }

        val frozen = ledgerHits("/MONTH/2026-08-31/frozen-closing-balance")
        assertThat(frozen).hasSize(1)
        assertThat(frozen[0].getHeader("Authorization")).isEqualTo("Bearer finrep-e2e-default-token")
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `COREP C_02_00 reads the TIED_OUT run as finrep's own m2m identity and never the untied one`() {
        Given { this } When {
            get("/api/v1/corep/templates/C_02.00?asOf=2026-06-30")
        } Then {
            statusCode(200)
            body("templateId", equalTo("C_02.00"))
            body(
                "cells.find { it.rowRef == 'r0040' && !it.isDataGap }.value",
                equalTo(6095896.08f),
            )
            body("cells.find { it.rowRef == 'r0140' }.value", equalTo(6093596.08f))
        }

        // Run selection on the wire: the list was fetched with the engine's page limit, and the
        // capital read went to the TIED_OUT run only — the NEWER UNTIED run at the same date was
        // listed but never fetched (ADR-0314 D3).
        val lists = riskHits("?limit=100")
        assertThat(lists).hasSize(1)
        assertThat(lists[0].getHeader("Authorization")).isEqualTo("Bearer finrep-e2e-m2m-token")
        assertThat(riskHits("/run-tied-jun/capital")).hasSize(1)
        assertThat(riskHits("/run-untied-jun/capital")).isEmpty()
        // The ledger was not asked for a risk-sourced template.
        assertThat(FinrepE2EWireMockResource.wm().findAll(getRequestedFor(urlEqualTo("/api/v1/ledger/periods"))))
            .isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `COREP C_72_00 renders LCR liquid assets from the same tied-out run`() {
        Given { this } When {
            get("/api/v1/corep/templates/C_72.00?asOf=2026-06-30")
        } Then {
            statusCode(200)
            body("templateId", equalTo("C_72.00"))
            // Total liquid assets: 1 610 market value, 1 525 after haircuts.
            body("cells.find { it.rowRef == 'r0010' && it.colRef == 'c0010' }.value", equalTo(1610))
            body("cells.find { it.rowRef == 'r0010' && it.colRef == 'c0040' }.value", equalTo(1525))
            body("cells.find { it.rowRef == 'r0020' && it.colRef == 'c0010' }.value", equalTo(1250))
        }

        assertThat(riskHits("/run-tied-jun/liquidity")).hasSize(1)
        assertThat(riskHits("/run-untied-jun/liquidity")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a report date with no TIED_OUT run renders flagged gaps, never a 5xx`() {
        Given { this } When {
            get("/api/v1/corep/templates/C_02.00?asOf=2026-09-30")
        } Then {
            statusCode(200)
            body("templateId", equalTo("C_02.00"))
            body("cells.find { it.rowRef == 'r0040' }.isDataGap", equalTo(true))
            body("cells.find { it.rowRef == 'r0040' }.gapReason", containsString("TIED_OUT"))
        }

        // Only the run list was asked; no capital endpoint was fetched for a run the adapter
        // refused to select.
        assertThat(riskHits("?limit=100")).hasSize(1)
        assertThat(riskHits("/run-untied-sep/capital")).isEmpty()
        assertThat(riskHits("/run-untied-sep/liquidity")).isEmpty()
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `the periods endpoint lists only immutable frozen month evidence`() {
        Given { this } When { get("/api/v1/finrep/periods") } Then {
            statusCode(200)
            body("latest", equalTo("2026-06-30"))
            body("periods", hasSize<Any>(2))
            body("periods", hasItem("2026-06-30"))
            body("periods", hasItem("2026-05-31"))
            // The DRAFT month, the YEAR period and the legacy HASH_ONLY month are filtered out.
            body("periods", not(hasItem("2026-07-31")))
            body("periods", not(hasItem("2026-12-31")))
            body("periods", not(hasItem("2026-04-30")))
        }

        // The full-range scan rides the wire exactly as the adapter declares it.
        assertThat(ledgerHits("?from=1970-01-01&to=9999-12-31")).hasSize(1)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `XBRL-CSV preflight blocks an unbalanced preview and clears a balanced one`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01/xbrl-csv/preflight?asOf=2026-07-31")
        } Then {
            statusCode(200)
            body("templateId", equalTo("F01.01"))
            body("state", equalTo("BLOCKED"))
            body("blockers.code", hasItem("TRIAL_BALANCE_NOT_AGREED"))
        }

        Given { this } When {
            get("/api/v1/finrep/templates/F01.01/xbrl-csv/preflight?asOf=2026-06-30")
        } Then {
            statusCode(200)
            body("state", equalTo("READY_FOR_RENDERING"))
            body("blockers", empty<Any>())
            body("reportingFrameworkVersion", not(equalTo("")))
            body("dpmVersion", not(equalTo("")))
        }
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `a ledger outage surfaces as a 503 with the frozen read journal-provable`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01?asOf=2026-10-31&evidence=FROZEN")
        } Then {
            statusCode(503)
        }
        assertThat(ledgerHits("/MONTH/2026-10-31/frozen-closing-balance")).hasSize(1)
    }

    @Test
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `an unknown COREP template is refused before any downstream is asked`() {
        Given { this } When {
            get("/api/v1/corep/templates/C_99.99?asOf=2026-06-30")
        } Then {
            statusCode(400)
        }
        val ledgerAsked = FinrepE2EWireMockResource.wm()
            .findAll(getRequestedFor(urlEqualTo("/api/v1/ledger/periods")))
        assertThat(ledgerAsked).isEmpty()
        val riskAsked = FinrepE2EWireMockResource.wm()
            .findAll(getRequestedFor(urlEqualTo("/api/v1/risk/snapshots?limit=100")))
        assertThat(riskAsked).isEmpty()
    }

    @Test
    @TestSecurity(user = "analyst-1", roles = ["ROLE_VIEWER"])
    fun `the regulatory feed is operator-and-admin only`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01?asOf=2026-06-30")
        } Then {
            statusCode(403)
        }
        Given { this } When { get("/api/v1/finrep/periods") } Then { statusCode(403) }
        // Refused before any downstream is asked.
        assertThat(FinrepE2EWireMockResource.wm().findAll(getRequestedFor(urlEqualTo("/api/v1/ledger/periods"))))
            .isEmpty()
    }

    @Test
    fun `an anonymous caller is refused on the regulatory feed`() {
        Given { this } When {
            get("/api/v1/finrep/templates/F01.01?asOf=2026-06-30")
        } Then {
            statusCode(401)
        }
        Given { this } When { get("/api/v1/corep/templates/C_01.00?asOf=2026-06-30") } Then { statusCode(401) }
        Given { this } When { get("/api/v1/finrep/periods") } Then { statusCode(401) }
    }
}
