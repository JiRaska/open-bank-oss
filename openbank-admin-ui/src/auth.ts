// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import NextAuth from "next-auth"
import { authOptions } from "@/lib/auth/authOptions"

export const { handlers, auth, signIn, signOut } = NextAuth(authOptions)
