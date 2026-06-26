// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.analytics

/**
 * The maker-checker primitive was generalised out of `libs/analytics` into `libs/governance`
 * by ADR-0034 (it is not analytics-specific — runtime control actions use it too). These
 * aliases keep existing analytics call sites compiling; they migrate to
 * `com.openbank.libs.governance` opportunistically. The canonical definition lives in
 * [com.openbank.libs.governance.Proposal].
 */
typealias Proposal<T> = com.openbank.libs.governance.Proposal<T>
typealias ProposalState = com.openbank.libs.governance.ProposalState
typealias MakerCheckerViolation = com.openbank.libs.governance.MakerCheckerViolation
