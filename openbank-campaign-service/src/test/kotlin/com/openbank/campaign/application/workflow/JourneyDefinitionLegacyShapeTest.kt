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
 * The replay-safety argument for the #3585 branch conditions and the #4781/ADR-0260 decision
 * graph, as a test rather than a claim.
 *
 * `CampaignJourneyWorkflowImpl` calls the new `previousDeliveryStatus` activity only when a step
 * carries a [com.openbank.campaign.domain.model.StepCondition]. An in-flight journey replays the
 * `loadDefinition` result out of its Temporal history — JSON written before the field existed — so
 * the whole argument reduces to one question: does that legacy JSON still deserialize, with
 * `condition` null? If it did not, every running journey would either fail to replay or take a
 * branch its history never recorded. The identical argument applies to `decisions` and
 * `nextStepOrder`, added later by #4781 (ADR-0260 D1): a journey started before that PR has a
 * `JourneyDefinition` in its history with no `decisions` key at all, and a step with no
 * `nextStepOrder` key.
 *
 * Decoded through the very converter the production client is built with
 * (`TemporalClientProducer.kotlinAwareDataConverter`, mirrored in
 * [CampaignJourneyWorkflowTest.kotlinAwareDataConverter]), not a hand-built ObjectMapper: the
 * converter the replay path uses is the only one whose behaviour is evidence here.
 */
class JourneyDefinitionLegacyShapeTest {

    private val legacyStepJson =
        """{"order":0,"template":"MARKETING_PRODUCT_OFFER","channel":"EMAIL","variables":{},"delaySeconds":60}"""

    /**
     * The exact wire shape of a `CampaignStep` and `JourneyDefinition` immediately before #4781
     * (parent commit 3cc3fe087^): `condition`/`conditionSourceOrder` already exist (#3585), but
     * `decisions` and `nextStepOrder` do not — neither key is present here.
     */
    private val preGraphStepJson = """{"order":0,"template":"MARKETING_PRODUCT_OFFER","channel":"EMAIL",""" +
        """"variables":{},"delaySeconds":60,"conditionSourceOrder":null}"""

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

    @Test
    fun `a definition recorded before the decision graph existed replays with no decisions and no next step`() {
        // Same argument as the branch-condition test above, one field generation later (ADR-0260
        // Phase A(b)): an in-flight journey's history predating #4781 has no `decisions` key on
        // JourneyDefinition and no `nextStepOrder` key on its step — both must default rather than
        // fail deserialization, through the production kotlinAwareDataConverter.
        val legacy = """{"steps":[$preGraphStepJson],"stopCondition":null}"""

        val definition = CampaignJourneyWorkflowTest.kotlinAwareDataConverter().fromPayload(
            payload(legacy),
            JourneyDefinition::class.java,
            JourneyDefinition::class.java,
        )

        assertThat(definition.decisions).isEmpty()
        assertThat(definition.steps).hasSize(1)
        assertThat(definition.steps[0].nextStepOrder).isNull()
        assertThat(definition.steps[0].conditionSourceOrder).isNull()
    }

    @Test
    fun `a campaign row written before the decision graph existed reads with no next step`() {
        // The other persistence path (V2 JSON text column), same generation.
        val step: CampaignStep = ObjectMapper().registerKotlinModule().readValue(preGraphStepJson)

        assertThat(step.nextStepOrder).isNull()
    }
}
