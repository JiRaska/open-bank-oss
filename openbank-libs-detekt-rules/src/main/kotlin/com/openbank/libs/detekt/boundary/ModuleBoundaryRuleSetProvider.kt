// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.detekt.boundary

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class ModuleBoundaryRuleSetProvider : RuleSetProvider {

    override val ruleSetId = "openbank-module-boundaries"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(GamificationModuleBoundaryRule(config)),
    )
}
