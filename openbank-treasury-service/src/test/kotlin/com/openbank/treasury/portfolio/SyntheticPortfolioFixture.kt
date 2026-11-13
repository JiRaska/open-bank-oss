// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.portfolio

/** ZZ identifiers are checksum-valid synthetic test instruments, not issuer classifications of real securities. */
fun String.withSyntheticPortfolioIsins(): String = replace("CZ0001005037", "ZZ0000000016")
    .replace("CZ0001006266", "ZZ0000000024")
    .replace("CZ0008008018", "ZZ0000000032")
    .replace("IE00B4L5Y983", "ZZ0000000040")
