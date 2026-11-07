// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://github.com/JiRaska/open-bank-oss/blob/main/LICENSE.

package com.openbank.document.contract

import io.quarkus.test.junit.QuarkusTestProfile

/** Share one application and renderer lifecycle across all provider verification classes. */
class DocumentProviderProfile : QuarkusTestProfile {
    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(QuarkusTestProfile.TestResourceEntry(PdfRendererStubResource::class.java))
}
