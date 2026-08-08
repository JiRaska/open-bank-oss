// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.contact

/**
 * Marks a function as a marketing-class call site reaching the delivery/surface layer (ADR-0219
 * D4): a notification category dispatch, a campaign journey step, an engagement surface
 * resolution/event, an agent-proposed contact, or an RM-initiated send. `openbank-libs-detekt-rules`'s
 * `MarketingCallSiteWiringRule` fails the build when an annotated function's containing class has
 * no `com.openbank.libs.contact.ContactPolicyGate` injected, or the function makes no `.check(`
 * call against it — the compile-time wiring assertion D4 requires instead of a convention every
 * sender is trusted to remember. Source-retained: read only by the detekt rule and human readers,
 * never at runtime.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class MarketingCallSite
