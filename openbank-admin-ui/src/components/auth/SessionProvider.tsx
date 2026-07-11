// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

"use client"
import { SessionProvider as NextAuthSessionProvider } from "next-auth/react"
import { ReauthOnExpiry } from "./ReauthOnExpiry"

export function SessionProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextAuthSessionProvider>
      <ReauthOnExpiry />
      {children}
    </NextAuthSessionProvider>
  )
}
