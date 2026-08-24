// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

/**
 * Declares that a MicroProfile REST client deliberately terminates synthetic-taint propagation.
 *
 * Internal OpenBank edges register [SyntheticTaintClientFilter], so a synthetic journey stays
 * identifiable until its persisted/event boundary. An external feed, observability backend or LLM
 * endpoint must not receive that banking-internal marker merely because a caller happens to be in
 * a synthetic request. Such a client declares this annotation instead, with a reason that is
 * visible beside the endpoint rather than hidden in a central exception list.
 *
 * The static fleet gate requires exactly one of this annotation or
 * `@RegisterProvider(SyntheticTaintClientFilter::class)` on every `@RegisterRestClient` interface.
 * This annotation does not make an internal edge safe to exempt: it is an explicit review boundary,
 * not a runtime bypass.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SyntheticTaintExternalBoundary(val reason: String)
