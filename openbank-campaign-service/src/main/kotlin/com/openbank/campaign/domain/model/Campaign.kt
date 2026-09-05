// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.campaign.domain.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The editable definition of a draft, deliberately separate from [Campaign]'s server-owned
 * identity, lifecycle and audit fields. A maker supplies this whole value; revision can therefore
 * never accidentally preserve an old field merely because another endpoint forgot to pass it.
 */
data class CampaignDefinition(
    val name: String,
    val goal: String,
    /**
     * Mandatory: no default, so a maker cannot omit it and a caller cannot forget to pass it.
     * `goal` is free text and cannot carry this — a gate keyed on prose fails open on a typo, a
     * translation, or a maker who writes "Q4 cash push" instead of "loan".
     */
    val productKind: CampaignProductKind,
    val segmentRef: SegmentRef,
    val steps: List<CampaignStep>,
    val stopCondition: StopCondition? = null,
    val conversionRule: String? = null,
    val holdoutPercent: Int = 0,
    val schedule: CampaignSchedule? = null,
    val trigger: String? = null,
    /**
     * Explicit, reviewed delivery decisions.  An empty list preserves the original linear
     * journey exactly; a non-empty list opts the draft into the bounded graph model below.
     */
    val decisions: List<CampaignDecision> = emptyList(),
    /** Immutable published incentive catalogue reference; redemption remains incentive-service-owned. */
    val incentiveOfferRef: IncentiveOfferRef? = null,
)

data class IncentiveOfferRef(val id: UUID, val name: String, val version: Int) {
    init {
        require(name.isNotBlank()) { "incentive offer name must not be blank" }
        require(version > 0) { "incentive offer version must be positive" }
    }
}

/**
 * Campaign aggregate (ADR-0200). A definition — steps, delays, stop conditions — that is executed
 * as one Temporal workflow per enrolled party. Content is composed from the notification-service
 * template catalogue with declared variables; free-form bodies are rejected by construction
 * (ADR-0176 D4 / ADR-0200 D4).
 */
data class Campaign(
    val id: UUID,
    val name: String,
    val goal: String,
    /** See [CampaignProductKind]. Fixed once the draft leaves DRAFT — [revise] is draft-only. */
    val productKind: CampaignProductKind,
    val segmentRef: SegmentRef,
    val steps: List<CampaignStep>,
    val stopCondition: StopCondition? = null,
    /**
     * Catalogue key from [ConversionCatalog], or null (ADR-0245 D1). Null is the honest resting
     * state: a campaign with no rule reports no conversions, which is different from — and must
     * never be rendered as — a campaign that converted nobody.
     */
    val conversionRule: String? = null,
    /**
     * Percentage of the eligible audience assigned to a no-contact control cohort. A control is
     * meaningful only when there is an observed outcome to compare, so it is unavailable without
     * a [conversionRule]. Fifty percent is an intentional safety ceiling: an author can measure a
     * campaign without accidentally withholding it from most of the audience.
     */
    val holdoutPercent: Int = 0,
    /**
     * Cadence key from [ScheduleCatalog], or null for the one-shot campaign that was the only kind
     * until now. Null means `POST /{id}/enrol` is the only way in, exactly as before.
     */
    val schedule: CampaignSchedule? = null,
    /**
     * Key from [TriggerCatalog], or null. When set, a matching product event enrols the party at
     * once — but only if the segment still contains them: the trigger decides when, the segment
     * decides who.
     */
    val trigger: String? = null,
    val state: CampaignState,
    val createdBy: String,
    val approvedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Explicit graph nodes; persisted separately from legacy step JSON for reversible rollout. */
    val decisions: List<CampaignDecision> = emptyList(),
    val incentiveOfferRef: IncentiveOfferRef? = null,
) {
    init {
        require(name.isNotBlank()) { "campaign name must not be blank" }
        require(steps.isNotEmpty()) { "campaign must have at least one step" }
        require(steps.size <= MAX_STEPS) { "journeys are capped at $MAX_STEPS steps in the first slice" }
        require(steps.map { it.order }.distinct().size == steps.size) { "campaign step orders must be unique" }
        steps.forEach { step ->
            step.conditionSourceOrder?.let { sourceOrder ->
                require(step.condition != null) { "a condition source requires a branch condition" }
                require(sourceOrder < step.order) { "a condition source must be an earlier step" }
                require(steps.any { it.order == sourceOrder }) { "a condition source must name a campaign step" }
            }
        }
        validateDecisionGraph(steps, decisions)
        require(holdoutPercent in 0..MAX_HOLDOUT_PERCENT) {
            "holdoutPercent must be between 0 and $MAX_HOLDOUT_PERCENT"
        }
        require(holdoutPercent == 0 || conversionRule != null) {
            "a holdout requires a conversionRule to measure its outcome"
        }
        // A/B assignment is campaign-wide, not a step-level surprise. Letting the first message
        // have two variants while a later one silently collapses them would turn the result into
        // an unreviewable mixture of different treatments. Existing campaigns have no B variables
        // on any step and keep their exact pre-experiment path.
        val contentExperiment = steps.firstOrNull()?.variantBVariables != null
        require(steps.all { (it.variantBVariables != null) == contentExperiment }) {
            "a content experiment must define variant B for every campaign step"
        }
        require(!contentExperiment || conversionRule != null) {
            "a content experiment requires a conversionRule to measure its outcome"
        }
    }

    /** Both variants exist on every step; a null B map means this is not a content experiment. */
    val hasContentExperiment: Boolean get() = steps.firstOrNull()?.variantBVariables != null

    /** DRAFT → PENDING_APPROVAL → ACTIVE ⇄ PAUSED → CLOSED. Terminal states never leave. */
    fun submit(): Campaign {
        require(state == CampaignState.DRAFT) { "only a DRAFT campaign can be submitted for approval" }
        return copy(state = CampaignState.PENDING_APPROVAL, updatedAt = Instant.now())
    }

    /**
     * A maker may revise the definition while it is still a draft.  Once it enters approval, the
     * exact reviewed definition is immutable: changing it afterwards would make the checker
     * approve one journey while a different one is actually run.
     */
    fun revise(definition: CampaignDefinition): Campaign {
        require(state == CampaignState.DRAFT) { "only a DRAFT campaign can be revised" }
        return copy(
            name = definition.name,
            goal = definition.goal,
            // Revisable only because this method already refuses anything but a DRAFT. Once a
            // campaign is submitted for approval the kind is frozen, so the consent governing the
            // parties it enrols cannot change under them mid-flight.
            productKind = definition.productKind,
            segmentRef = definition.segmentRef,
            steps = definition.steps.sortedBy { it.order },
            stopCondition = definition.stopCondition,
            conversionRule = definition.conversionRule,
            holdoutPercent = definition.holdoutPercent,
            schedule = definition.schedule,
            trigger = definition.trigger,
            decisions = definition.decisions,
            incentiveOfferRef = definition.incentiveOfferRef,
            updatedAt = Instant.now(),
        )
    }

    fun activate(approver: String): Campaign {
        require(state == CampaignState.PENDING_APPROVAL) { "only a PENDING_APPROVAL campaign can activate" }
        require(approver != createdBy) { "maker/checker: the approver must differ from the creator (ADR-0200 D5)" }
        return copy(state = CampaignState.ACTIVE, approvedBy = approver, updatedAt = Instant.now())
    }

    fun pause(): Campaign {
        require(state == CampaignState.ACTIVE) { "only an ACTIVE campaign can pause" }
        return copy(state = CampaignState.PAUSED, updatedAt = Instant.now())
    }

    fun resume(): Campaign {
        require(state == CampaignState.PAUSED) { "only a PAUSED campaign can resume" }
        return copy(state = CampaignState.ACTIVE, updatedAt = Instant.now())
    }

    fun close(): Campaign {
        require(state == CampaignState.ACTIVE || state == CampaignState.PAUSED) {
            "only an ACTIVE or PAUSED campaign can close"
        }
        return copy(state = CampaignState.CLOSED, updatedAt = Instant.now())
    }

    companion object {
        const val MAX_STEPS = 5

        /** A deliberate ceiling: rich marketer paths, never an unreviewable arbitrary DAG. */
        const val MAX_DECISIONS = 3
        const val MAX_HOLDOUT_PERCENT = 50
    }
}

/**
 * A reviewable binary branch after one delivery step.  The predicate is deliberately fixed to
 * notification-service's durable delivery fact: `confirmed` versus `not confirmed`.  The latter
 * is not an invented open/click signal and includes PENDING, FAILED and a still-absent receipt.
 *
 * Both edges point only forward.  This makes the graph acyclic by construction, keeps a five-step
 * campaign legible, and lets a workflow recover the exact selected path from durable records.
 */
data class CampaignDecision(
    val sourceStepOrder: Int,
    val evaluationDelaySeconds: Long = 0,
    val confirmedStepOrder: Int,
    val notConfirmedStepOrder: Int,
) {
    init {
        require(sourceStepOrder >= 0) { "a decision source must be >= 0" }
        require(evaluationDelaySeconds >= 0) { "a decision delay must be >= 0" }
        require(confirmedStepOrder > sourceStepOrder) { "a confirmed path must point forward" }
        require(notConfirmedStepOrder > sourceStepOrder) { "a not-confirmed path must point forward" }
        require(confirmedStepOrder != notConfirmedStepOrder) { "decision paths must have different targets" }
    }
}

/**
 * Validate the small, directed journey topology.  Legacy conditional steps remain executable but
 * cannot be mixed with a graph: otherwise a branch may look explicit in Studio while Temporal is
 * still silently walking the legacy order-based condition path.
 */
private fun validateDecisionGraph(steps: List<CampaignStep>, decisions: List<CampaignDecision>) {
    if (decisions.isEmpty()) return
    require(decisions.size <= Campaign.MAX_DECISIONS) {
        "journeys are capped at ${Campaign.MAX_DECISIONS} explicit decisions"
    }
    require(steps.none { it.condition != null || it.conditionSourceOrder != null }) {
        "legacy conditions and explicit decisions cannot be mixed"
    }
    val orders = steps.map { it.order }.toSet()
    val firstOrder = requireNotNull(steps.minOfOrNull { it.order }) { "a graph journey needs a step" }
    require(decisions.map { it.sourceStepOrder }.distinct().size == decisions.size) {
        "a step may have only one explicit decision"
    }
    decisions.forEach { decision ->
        require(decision.sourceStepOrder in orders) { "a decision source must name a campaign step" }
        require(decision.confirmedStepOrder in orders) { "a confirmed path must name a campaign step" }
        require(decision.notConfirmedStepOrder in orders) { "a not-confirmed path must name a campaign step" }
    }
    // A node that is not the source of an explicit decision has one deterministic forward edge.
    // Absent nextStepOrder is terminal; that is intentional, not a hidden linear fallback.
    val decisionsBySource = decisions.associateBy { it.sourceStepOrder }
    steps.forEach { step ->
        step.nextStepOrder?.let { next ->
            require(step.order !in decisionsBySource) { "a decision source cannot also have a direct next step" }
            require(next in orders) { "a direct path must name a campaign step" }
            require(next > step.order) { "a direct path must point forward" }
        }
    }
    val reachable = mutableSetOf<Int>()
    fun visit(order: Int) {
        if (!reachable.add(order)) return
        val decision = decisionsBySource[order]
        if (decision != null) {
            visit(decision.confirmedStepOrder)
            visit(decision.notConfirmedStepOrder)
        } else {
            steps.first { it.order == order }.nextStepOrder?.let(::visit)
        }
    }
    visit(firstOrder)
    require(reachable == orders) { "every campaign step must be reachable from its first step" }
}

/**
 * A stable experimental assignment. It is stored with the enrolment as well as derived here: the
 * stored value is the audit record, while deriving before creating a journey makes retries choose
 * exactly the same customer cohort. SHA-256 is used instead of `UUID.hashCode()`, whose intent is
 * object hashing rather than a documented experiment bucket.
 */
enum class ExperimentCohort {
    TREATMENT,
    HOLDOUT,
    ;

    companion object {
        fun assign(campaignId: UUID, partyId: UUID, holdoutPercent: Int): ExperimentCohort {
            require(holdoutPercent in 0..Campaign.MAX_HOLDOUT_PERCENT) {
                "holdoutPercent must be between 0 and ${Campaign.MAX_HOLDOUT_PERCENT}"
            }
            if (holdoutPercent == 0) return TREATMENT
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$campaignId:$partyId".toByteArray(StandardCharsets.UTF_8))
            val bucket = (ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int.toLong() and UNSIGNED_INT_MASK) % BUCKETS
            return if (bucket < holdoutPercent * PERCENT_TO_BUCKET) HOLDOUT else TREATMENT
        }

        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
        private const val BUCKETS = 10_000L
        private const val PERCENT_TO_BUCKET = 100L
    }
}

/**
 * The durable arm of a content experiment. `A` is the author's original declared values and `B`
 * is the alternative. Keeping the names neutral avoids declaring a winner before there is data.
 */
enum class ContentVariant {
    A,
    B,
    ;

    companion object {
        /** A stable 50/50 split, independent from the campaign's no-contact holdout split. */
        fun assign(campaignId: UUID, partyId: UUID): ContentVariant {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("content:$campaignId:$partyId".toByteArray(StandardCharsets.UTF_8))
            val bucket = (ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int.toLong() and UNSIGNED_INT_MASK) % BUCKETS
            return if (bucket < BUCKETS / 2) A else B
        }

        private const val UNSIGNED_INT_MASK = 0xffff_ffffL
        private const val BUCKETS = 10_000L
    }
}

enum class CampaignState { DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, CLOSED }

/**
 * What a campaign is selling, as far as consent is concerned (ADR-0269 rule 1).
 *
 * MANDATORY on every campaign and deliberately NOT nullable. A nullable field would make "this is
 * not a credit campaign" and "nobody said what this is" the same value, and the whole reason the
 * field exists is that the credit step gate must be able to tell them apart — the same three-valued
 * discipline `CourtRegisterSignalState` keeps on the lending side.
 *
 * The credit members reuse the origination vocabulary (`CreditProductKind` in openbank-libs-domain)
 * rather than inventing a parallel one: a campaign for an unsecured loan and the journey it enrols
 * into must not be able to disagree about what an unsecured loan is.
 */
enum class CampaignProductKind {
    /** Not a credit campaign. The overwhelming majority, and an explicit statement rather than a gap. */
    NONE,

    /** Cash loan. */
    UNSECURED,

    /** Mortgage or car loan. */
    SECURED,

    /** Overdraft, credit card, instalment limit. */
    REVOLVING,

    ;

    /** Whether ADR-0269's credit consent and suppression floor govern this campaign. */
    val isCredit: Boolean get() = this != NONE
}

/**
 * One journey step: a catalogue template with declared variables, delivered on a channel after a
 * delay from the previous step.
 *
 * ADR-0200 D7 shipped this EMAIL-only behind three named blockers. Two have since cleared: the
 * per-channel marketing consent scope exists (`MARKETING_COMMS_PUSH`, ADR-0198 D4) and #1182 is
 * closed — push bodies are generic by construction. BANNER is a first-party home-surface
 * placement, consented as an in-app impression rather than as a notification.
 */
data class CampaignStep(
    val order: Int,
    val template: String,
    val channel: Channel,
    val variables: Map<String, String>,
    val delaySeconds: Long,
    /**
     * The ADR-0200 D1 branch condition (#3585): when set, this step runs only if the condition
     * holds, and is otherwise skipped — the journey continues to the next step rather than ending.
     *
     * Defaulted to null, and that default is load-bearing beyond convenience: a step serialized
     * before this field existed — in a campaign row, and in the Temporal history of an in-flight
     * journey — deserializes with no condition, so a running workflow takes exactly the code path
     * it took before. See [StepCondition].
     */
    val condition: StepCondition? = null,
    /**
     * Optional explicit source for [condition].  Absent preserves the original "latest earlier
     * delivery" semantics.  When present, both arms of a decision can name the same source step,
     * so a skipped arm can never accidentally become the other's predecessor.
     */
    val conditionSourceOrder: Int? = null,
    /**
     * Alternative declared values for the B arm of a campaign-wide A/B content experiment.
     * Null preserves the historical single-content step; when present every step must provide it
     * (enforced by [Campaign]) so one party keeps the same treatment throughout the journey.
     */
    val variantBVariables: Map<String, String>? = null,
    /**
     * If the primary EMAIL step has no active e-mail marketing consent, retry the same offer as a
     * PUSH only after its own full contact-policy check succeeds. This is intentionally not a
     * generic failover: quiet hours, caps and suppression lists are channel-independent policy
     * decisions and must never be bypassed; an asynchronous mail bounce is also not a safe prompt
     * to send a second message.
     */
    val fallbackToPush: Boolean = false,
    /** A closed, app-owned destination for a push tap — never author-entered URL text. */
    val mobileDestination: MobileDestination? = null,
    /**
     * The authenticated-app slot a BANNER step is allowed to occupy. Null keeps placements created
     * before multi-surface support on HOME_BANNER; it is resolved before a command leaves this
     * service, so engagement never has to guess a customer's intended surface.
     */
    val inAppSurface: InAppSurface? = null,
    /**
     * The optional delivery shape for the B arm of a journey experiment. Leaving all three values
     * absent keeps the existing copy-only experiment; providing a template and channel lets a
     * marketer compare a real path, such as e-mail today against an app push tomorrow.
     */
    val variantBTemplate: String? = null,
    val variantBChannel: Channel? = null,
    val variantBDelaySeconds: Long? = null,
    /**
     * The one deterministic edge out of this delivery node in an explicit decision journey.
     * Null is a deliberate terminal.  It is ignored for legacy linear definitions, which keeps
     * rows written before graph support byte-for-byte compatible.
     */
    val nextStepOrder: Int? = null,
) {
    init {
        require(order >= 0) { "step order must be >= 0" }
        require(delaySeconds >= 0) { "step delay must be >= 0" }
        // The template decides the channel, and the step must agree. Two ways to get this wrong,
        // both silent: an EMAIL step naming a push template renders a one-line title as a whole
        // email, and a PUSH step naming an email template puts the offer body into an APNs payload
        // — the leak #1182 closed. Neither surfaces until a real send.
        require(TemplateCatalog.CHANNEL_OF[template] == null || TemplateCatalog.CHANNEL_OF[template] == channel) {
            "template '$template' renders on ${TemplateCatalog.CHANNEL_OF[template]}, not $channel"
        }
        // Rejected by construction, not validated at the edge: a step that names a template nobody
        // renders, or passes a variable nobody declared, is only discovered while composing the
        // notification — long after the campaign was approved (ADR-0221 D1, ADR-0176 D4).
        //
        // Deliberately NOT requiring every declared variable to be present. That is stricter than
        // the renderer itself (NotificationTemplate.unknownVariables checks only the unknown
        // direction), and it would make a partially-filled draft unrepresentable. Completeness is
        // an authoring rule, enforced by the wizard before submit — see TemplateCatalog.
        require(TemplateCatalog.exists(template)) {
            "unknown template '$template' — ${TemplateCatalog.MARKETING_ONLY_REASON}"
        }
        TemplateCatalog.unknownVariables(template, variables).let {
            require(it.isEmpty()) { "template '$template' does not declare ${it.sorted()}" }
        }
        variantBVariables?.takeIf { variantBTemplate == null }?.let { alternative ->
            TemplateCatalog.unknownVariables(template, alternative).let {
                require(it.isEmpty()) { "template '$template' does not declare ${it.sorted()}" }
            }
        }
        require(!fallbackToPush || channel == Channel.EMAIL) {
            "only an EMAIL step can fall back to PUSH"
        }
        require(!fallbackToPush || template in TemplateCatalog.PUSH_FALLBACK_FOR_EMAIL) {
            "template '$template' has no safe PUSH fallback"
        }
        require(
            mobileDestination == null ||
                channel == Channel.PUSH ||
                channel == Channel.BANNER ||
                variantBChannel == Channel.PUSH ||
                variantBChannel == Channel.BANNER ||
                fallbackToPush,
        ) {
            "a mobile destination requires a PUSH or BANNER path, or an EMAIL step with PUSH fallback"
        }
        require(inAppSurface == null || channel == Channel.BANNER) {
            "an in-app surface requires a BANNER step"
        }
        require(channel != Channel.BANNER || mobileDestination != null) {
            "a BANNER step requires a mobile destination"
        }
        if (channel == Channel.BANNER) {
            require(template == TemplateCatalog.templateForInAppSurface(inAppSurface ?: InAppSurface.HOME_BANNER)) {
                "template '$template' does not render on ${inAppSurface ?: InAppSurface.HOME_BANNER}"
            }
        }
        require((variantBTemplate == null) == (variantBChannel == null)) {
            "a variant B path needs both its template and channel"
        }
        require(variantBTemplate == null || variantBVariables != null) {
            "a variant B path needs variant B values so the campaign records an experiment"
        }
        require(variantBChannel != Channel.BANNER || mobileDestination != null) {
            "a BANNER variant B path requires a mobile destination"
        }
        require(variantBDelaySeconds == null || variantBDelaySeconds >= 0) {
            "variant B step delay must be >= 0"
        }
        variantBTemplate?.let { alternativeTemplate ->
            val alternativeChannel = requireNotNull(variantBChannel)
            require(TemplateCatalog.exists(alternativeTemplate)) {
                "unknown variant B template '$alternativeTemplate' — ${TemplateCatalog.MARKETING_ONLY_REASON}"
            }
            require(TemplateCatalog.CHANNEL_OF[alternativeTemplate] == alternativeChannel) {
                "variant B template '$alternativeTemplate' renders on ${TemplateCatalog.CHANNEL_OF[alternativeTemplate]}, not $alternativeChannel"
            }
            variantBVariables?.let { alternativeVariables ->
                TemplateCatalog.unknownVariables(alternativeTemplate, alternativeVariables).let {
                    require(it.isEmpty()) {
                        "variant B template '$alternativeTemplate' does not declare ${it.sorted()}"
                    }
                }
            }
            require(!fallbackToPush || alternativeChannel == Channel.EMAIL) {
                "an EMAIL fallback cannot be shared with a non-EMAIL variant B path"
            }
            if (alternativeChannel == Channel.BANNER) {
                require(alternativeTemplate == TemplateCatalog.templateForInAppSurface(InAppSurface.HOME_BANNER)) {
                    "a variant B BANNER path uses the reviewed HOME_BANNER card"
                }
            }
        }
    }

    /** Resolves content after the durable enrolment assignment, never randomly at send time. */
    fun variablesFor(variant: ContentVariant?): Map<String, String> =
        if (variant == ContentVariant.B) variantBVariables ?: variables else variables

    /** The persisted cohort chooses delivery structure as well as copy; retries cannot reshuffle it. */
    fun delayFor(variant: ContentVariant?): Long =
        variantBDelaySeconds.takeIf { variant == ContentVariant.B } ?: delaySeconds

    private fun templateFor(variant: ContentVariant?): String =
        variantBTemplate.takeIf { variant == ContentVariant.B } ?: template

    private fun channelFor(variant: ContentVariant?): Channel =
        variantBChannel.takeIf { variant == ContentVariant.B } ?: channel

    /** Primary delivery values, kept separate from the fallback's reduced template vocabulary. */
    fun primaryDelivery(variant: ContentVariant?): CampaignDelivery {
        val selectedChannel = channelFor(variant)
        val selectedTemplate = templateFor(variant)
        val selectedInAppSurface = when {
            selectedChannel != Channel.BANNER -> null
            variant == ContentVariant.B && variantBTemplate != null -> InAppSurface.HOME_BANNER
            else -> inAppSurface ?: InAppSurface.HOME_BANNER
        }
        return CampaignDelivery(
            selectedChannel,
            selectedTemplate,
            TemplateCatalog.valuesFor(selectedTemplate, variablesFor(variant)),
            mobileDestination?.deepLink.takeIf {
                selectedChannel == Channel.PUSH || selectedChannel == Channel.BANNER
            },
            selectedInAppSurface,
        )
    }

    /** The only supported fallback: a consented app push after EMAIL consent was absent. */
    fun pushFallback(variant: ContentVariant?): CampaignDelivery? =
        TemplateCatalog.PUSH_FALLBACK_FOR_EMAIL[templateFor(variant)]
            ?.takeIf { fallbackToPush && channelFor(variant) == Channel.EMAIL }
            ?.let { pushTemplate ->
                CampaignDelivery(
                    Channel.PUSH,
                    pushTemplate,
                    TemplateCatalog.valuesFor(pushTemplate, variablesFor(variant)),
                    mobileDestination?.deepLink,
                )
            }
}

/** A resolved per-attempt delivery, including the channel that will be recorded for audit. */
data class CampaignDelivery(
    val channel: Channel,
    val template: String,
    val variables: Map<String, String>,
    val deepLink: String? = null,
    val inAppSurface: InAppSurface? = null,
)

/** The only destinations the mobile app contract recognises for campaign push and banner taps. */
enum class MobileDestination(val deepLink: String) {
    HOME("openbank://home"),
    SAVINGS("openbank://savings"),
    CARDS("openbank://cards"),
    PAYMENTS("openbank://payments"),
    PRODUCT_HUB("openbank://products"),
}

/** Closed authenticated-app inventory a campaign may use. */
enum class InAppSurface { HOME_BANNER, HOME_CAROUSEL, STORIES, PRODUCT_FEED, REWARDS_HUB }

/**
 * Delivery channels a campaign step may use.
 *
 * EMAIL, PUSH and BANNER only, and the omissions are decisions rather than gaps. SMS has no outbound port
 * anywhere in the platform (ADR-0200 D7). IN_APP was *removed* from `NotificationChannel` by #2372
 * because its dispatch branch silently dropped every message; re-adding it needs a terminal-status
 * transition and a wake-signal design, not an enum entry. Listing either here would let a campaign
 * be approved against a channel that delivers nothing — the "appearance of four channels" ADR-0200
 * D7 explicitly refuses.
 */
enum class Channel { EMAIL, PUSH, BANNER }

/**
 * The campaign's own stop condition (ADR-0200 D1, issue #3585 slice 1), evaluated by the journey
 * workflow BEFORE each step against observable state — never against a fabricated signal. Only
 * conditions backed by data the service already holds are representable: the first (and so far
 * only) one is the party's lifetime SENT count in this campaign, which the send log holds. A
 * `null` stop condition means the journey always runs every step, exactly as before.
 */
data class StopCondition(val maxSendsPerParty: Int) {
    init {
        require(maxSendsPerParty >= 1) { "maxSendsPerParty must be >= 1 — a zero cap sends nothing" }
    }

    /** True when [sendsSoFar] already reached the cap, so the journey must stop before the next step. */
    fun reachedBy(sendsSoFar: Int): Boolean = sendsSoFar >= maxSendsPerParty
}

/**
 * A branch condition on one step (ADR-0200 D1, issue #3585), evaluated by the journey workflow
 * immediately before the step.
 *
 * Like [StopCondition], only conditions backed by data this service already holds are
 * representable. The one observable fact about an earlier step is its `DeliveryStatus` as reported
 * back by notification-service (ADR-0239 D3) — so the vocabulary is named after exactly that and
 * nothing more. There is deliberately no `IF_PREVIOUS_OPENED` or `IF_GOAL_REACHED`: no impression,
 * click or conversion signal exists anywhere in the platform, and a condition nothing can ever
 * make true is the "inauthentic placeholder" ADR-0220 D5 refuses.
 *
 * `CONFIRMED` means the message was reported delivered. It does NOT mean read, and the honest
 * resting state `PENDING` counts as *not* confirmed — so a follow-up gated on
 * [IF_PREVIOUS_NOT_CONFIRMED] will also fire for a send whose outcome has simply not landed yet.
 * That is a real property of an at-least-once feedback channel, not a bug to be papered over: put
 * a delay on the step that is long enough for an outcome to arrive.
 *
 * "Previous" is the most recent send-log row for this party in this campaign at a LOWER step
 * order. A step with no such row has no observable predecessor, so [IF_PREVIOUS_CONFIRMED] does
 * not hold and [IF_PREVIOUS_NOT_CONFIRMED] does.
 */
enum class StepCondition {
    /** Run this step only if the previous step's delivery was confirmed. */
    IF_PREVIOUS_CONFIRMED,

    /** Run this step only if the previous step's delivery was NOT confirmed — the reminder case. */
    IF_PREVIOUS_NOT_CONFIRMED,
    ;

    /** Whether this step runs, given the [previous] step's delivery status (null = no predecessor). */
    fun holdsFor(previous: DeliveryStatus?): Boolean = when (this) {
        IF_PREVIOUS_CONFIRMED -> previous == DeliveryStatus.CONFIRMED
        IF_PREVIOUS_NOT_CONFIRMED -> previous != DeliveryStatus.CONFIRMED
    }
}

/**
 * A recurring campaign's cadence and the window it is allowed to run in.
 *
 * The schedule re-runs enrolment, nothing else. Each run evaluates the segment and starts a journey
 * for whoever newly qualifies; parties already enrolled are skipped by the existing
 * `findByCampaignAndParty` check, so a daily cadence does not re-contact yesterday's audience. That
 * is what makes re-running safe, and it is the same idempotency the manual endpoint already relies
 * on.
 *
 * @param cadence key into [ScheduleCatalog].
 * @param endAt when the schedule stops firing, or null to run until the campaign is paused or
 *   closed. A campaign is a finite thing in practice and an unbounded one is usually an oversight,
 *   but refusing null would force a fake far-future date, which is worse: it hides the intent.
 */
data class CampaignSchedule(val cadence: String, val endAt: Instant? = null) {
    init {
        // A cadence outside the catalogue is a schedule that could never be translated into a
        // Temporal spec — reject it here rather than at the adapter, where the campaign would
        // already be stored and would read as scheduled while never firing.
        require(ScheduleCatalog.exists(cadence)) {
            "unknown cadence '$cadence' — known: ${ScheduleCatalog.ALL.keys.sorted()}"
        }
    }

    /** True once [endAt] has passed, i.e. the schedule has nothing left to do. */
    fun expiredAt(now: Instant): Boolean = endAt != null && !now.isBefore(endAt)
}

/** A binding to a versioned segment artifact (ADR-0201 D1): never a query, always name@version. */
data class SegmentRef(val name: String, val version: Int) {
    init {
        require(name.isNotBlank()) { "segment name must not be blank" }
        require(version >= 1) { "segment version must be >= 1" }
    }
}
