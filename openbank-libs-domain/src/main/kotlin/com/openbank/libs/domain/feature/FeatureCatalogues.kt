// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

/**
 * Which declared feature catalogues are served ONLINE, and which exist offline only.
 *
 * WHY THIS EXISTS. ADR-0140's central promise is that "online/offline parity is enforced by a
 * shared computation, not by convention" and that "parity is tested, not assumed". The test that
 * enforces it, `FeatureParityIT`, names `VELOCITY_TXN_COUNT_H1` and `H24` by hand. That was complete
 * while those two were the whole catalogue — and stops being complete the moment a third feature is
 * declared, silently, because a hand-kept list of the thing it checks reads as full coverage rather
 * than as unchecked. This repo has been bitten by that shape repeatedly: the pact drift gate whose
 * scope was a hand-kept module list, and the topic-attribution table whose coverage had to be
 * derived from the config.
 *
 * So the unit of coverage is the CATALOGUE, and every catalogue must appear in exactly one of the
 * two lists below. A feature that belongs to no catalogue, or a catalogue that appears in neither
 * list, fails `FeatureCatalogueCoverageTest` — which turns "the parity gate covers two of seven"
 * from a silence into a number.
 *
 * WHAT OFFLINE-ONLY MEANS, AND WHAT IT DOES NOT. An offline-only catalogue has no online updater, so
 * nothing writes it to the online store and no consumer can read it at decision time. It therefore
 * cannot skew — there is no second materialisation to disagree with — but neither can it serve.
 * Listing a catalogue here is a statement that this is deliberate today, not that parity has been
 * checked. Moving a catalogue to [ONLINE_SERVED] without adding it to the parity test is what the
 * coverage test refuses.
 */
object FeatureCatalogues {

    /**
     * Catalogues with an online updater, and therefore inside the parity guarantee.
     *
     * `PHASE1_FEATURES` is fed by fraud-service's `FeatureOnlineUpdater` and replayed against the
     * offline computation by `FeatureParityIT` (ADR-0140 phase 1).
     */
    val ONLINE_SERVED: Map<String, List<FeatureDefinition>> = mapOf(
        "PHASE1_FEATURES" to PHASE1_FEATURES,
    )

    /**
     * Catalogues declared but not served online yet, each with the reason it is not.
     *
     * The reason is the point: an entry without one is indistinguishable from an oversight, and the
     * whole failure this file guards against is an omission that looks like a decision.
     */
    val OFFLINE_ONLY: Map<String, String> = mapOf(
        "MONEY_FLOW_FEATURES" to
            "#8792 / ADR-0282 phase 1. Declared for segment definition and backtesting; no online " +
            "updater exists because no decision path reads a settled money-flow feature at request " +
            "time yet. The online writer belongs with the first consumer, not ahead of it — a store " +
            "nobody reads cannot be shown to be right, and its parity test would assert nothing.",
    )

    /** Every catalogue this module declares, by name. */
    val ALL: Set<String> get() = ONLINE_SERVED.keys + OFFLINE_ONLY.keys

    /** Every feature reachable through an online-served catalogue. */
    val ONLINE_SERVED_FEATURES: List<FeatureDefinition> get() = ONLINE_SERVED.values.flatten()
}
