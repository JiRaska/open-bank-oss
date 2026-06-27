// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import NextAuth from "next-auth"
import { authOptions } from "@/lib/auth/authOptions"

export const { handlers, auth, signIn, signOut } = NextAuth(authOptions)
