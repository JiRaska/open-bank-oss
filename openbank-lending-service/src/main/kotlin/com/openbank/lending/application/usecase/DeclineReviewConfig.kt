// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * No Kotlin default values on these parameters: a default generates a synthetic constructor, Arc then
 * instantiates the bean through it, and every @ConfigProperty is silently ignored (see
 * CustomerIntakeConfig). `defaultValue` on the annotation is what supplies the fallback.
 */
@ApplicationScoped
class DeclineReviewConfig(
    /** How many days after an engine decline the customer may still ask for human review. */
    @param:ConfigProperty(name = "lending.decline-review.window-days", defaultValue = "30")
    val windowDays: Long,
)
