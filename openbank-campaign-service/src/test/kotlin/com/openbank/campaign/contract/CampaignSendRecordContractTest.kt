// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.campaign.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/** The next 4-space-indented (i.e. top-level components/schemas) key, marking a schema's end. */
private val NEXT_SCHEMA = Regex("""\n {4}\S""")

/**
 * Guards the published `SendRecord.outcome` enum against the [com.openbank.campaign.domain.model.SendOutcome]
 * domain enum it describes, without booting the app.
 *
 * `ConversionConsumer` persists `SendOutcome.CONVERTED` into the same `send_log.outcome` column
 * that `GET /api/v1/campaigns/{id}/sends` serializes verbatim (Persistence.kt), so a live response
 * can already carry `CONVERTED` — the openapi enum omitted it (#5962). A generated client
 * validating strictly against the documented enum would reject a value the service genuinely
 * returns; nothing here booted the app or hit an HTTP endpoint, since the served JSON is not
 * checked against `openapi.yaml` at runtime, so only reading the spec text itself can catch this
 * class of drift.
 */
class CampaignSendRecordContractTest {

    private val openapi = File("src/main/resources/openapi.yaml").readText()

    @Test
    fun `SendRecord outcome documents every value the send log can carry`() {
        val afterHeader = openapi.substringAfter("\n    SendRecord:\n")
        val end = NEXT_SCHEMA.find(afterHeader)?.range?.first ?: afterHeader.length
        val schema = afterHeader.substring(0, end)
        val outcomeBlock = schema.substringAfter("outcome:").substringBefore("occurredAt:")
        assertThat(outcomeBlock)
            .describedAs("SendRecord.outcome enum block")
            .contains("SENT")
            .contains("DRY_RUN")
            .contains("SUPPRESSED_CAP")
            .contains("SUPPRESSED_QUIET_HOURS")
            .contains("SUPPRESSED_CONSENT")
            .contains("SUPPRESSED_LIST")
            .contains("SKIPPED_CONDITION")
            .contains("FAILED")
            .contains("CONVERTED")
    }
}
