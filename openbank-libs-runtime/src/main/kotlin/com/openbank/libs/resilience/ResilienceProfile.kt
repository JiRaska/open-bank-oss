// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

/**
 * Declares which ADR-0321 profile an inter-service adapter method (or a whole adapter class)
 * follows. [value] is one of [ResilienceProfiles.ALL]; [ResilienceProfiles.CUSTOM] is the visible
 * deviation and must carry a [reason] beside the call.
 *
 * A marker only: it changes no runtime behaviour. The fault-tolerance annotations next to it,
 * built from the [ResilienceProfiles] constants, are what the runtime obeys; this is what a
 * reviewer — and the planned `resilience-profile` gate — reads.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ResilienceProfile(val value: String, val reason: String = "")
