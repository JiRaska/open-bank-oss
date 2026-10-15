// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.onboarding

import com.openbank.pension.application.onboarding.OnboardingService
import com.openbank.pension.application.onboarding.QuestionnaireService
import com.openbank.pension.domain.pack.JurisdictionPackRegistry
import com.openbank.pension.domain.questionnaire.QuestionSetRegistry
import com.openbank.pension.infrastructure.onboarding.pack.OnboardingRulesLoader
import com.openbank.pension.infrastructure.onboarding.pack.QuestionSetLoader
import com.openbank.pension.infrastructure.onboarding.pack.StaticQuestionSetRegistry
import io.quarkus.runtime.Startup
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import java.time.Clock

/**
 * CDI wiring for the data-driven questionnaire (issue #12384). `@Startup`: a product line without
 * a question set of its regime stops the pod at boot, not on the first questionnaire.
 */
@ApplicationScoped
class QuestionnaireBeans {

    @Produces
    @Startup
    @ApplicationScoped
    fun questionSets(): QuestionSetRegistry =
        StaticQuestionSetRegistry(QuestionSetLoader.loadAll(), OnboardingRulesLoader.loadAll())

    @Produces
    @ApplicationScoped
    fun questionnaireService(
        onboarding: OnboardingService,
        questionSets: QuestionSetRegistry,
        packs: JurisdictionPackRegistry,
        clock: Clock,
    ): QuestionnaireService = QuestionnaireService(onboarding, questionSets, packs, clock)
}
