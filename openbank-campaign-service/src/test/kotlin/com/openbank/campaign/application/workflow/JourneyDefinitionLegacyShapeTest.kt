// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.campaign.application.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.protobuf.ByteString
import com.openbank.campaign.domain.model.CampaignStep
import io.temporal.api.common.v1.Payload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The replay-safety argument for the #3585 branch conditions, as a test rather than a claim.
 *
 * `CampaignJourneyWorkflowImpl` calls the new `previousDeliveryStatus` activity only when a step
 * carries a [com.openbank.campaign.domain.model.StepCondition]. An in-flight journey replays the
 * `loadDefinition` result out of its Temporal history — JSON written before the field existed — so
 * the whole argument reduces to one question: does that legacy JSON still deserialize, with
 * `condition` null? If it did not, every running journey would either fail to replay or take a
 * branch its history never recorded.
 *
 * Decoded through the very converter the production client is built with
 * (`TemporalClientProducer.kotlinAwareDataConverter`, mirrored in
 * [CampaignJourneyWorkflowTest.kotlinAwareDataConverter]), not a hand-built ObjectMapper: the
 * converter the replay path uses is the only one whose behaviour is evidence here.
 */
class JourneyDefinitionLegacyShapeTest {

    private val legacyStepJson =
        """{"order":0,"template":"MARKETING_PRODUCT_OFFER","channel":"EMAIL","variables":{},"delaySeconds":60}"""

    private fun payload(json: String): Payload = Payload.newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
        .setData(ByteString.copyFromUtf8(json))
        .build()

    @Test
    fun `a definition recorded before branch conditions existed replays with no condition`() {
        val legacy = """{"steps":[$legacyStepJson],"stopCondition":null}"""

        val definition = CampaignJourneyWorkflowTest.kotlinAwareDataConverter().fromPayload(
            payload(legacy),
            JourneyDefinition::class.java,
            JourneyDefinition::class.java,
        )

        assertThat(definition.steps).hasSize(1)
        assertThat(definition.steps[0].condition).isNull()
        assertThat(definition.steps[0].delaySeconds).isEqualTo(60)
        assertThat(definition.stopCondition).isNull()
    }

    @Test
    fun `a campaign row written before branch conditions existed reads with no condition`() {
        // The same absence on the other persistence path: campaigns store their steps as a JSON
        // text column (V2), so an existing row has no `condition` key either.
        val step: CampaignStep = ObjectMapper().registerKotlinModule().readValue(legacyStepJson)

        assertThat(step.condition).isNull()
    }
}
