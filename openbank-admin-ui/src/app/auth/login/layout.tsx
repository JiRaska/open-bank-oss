// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Metadata } from 'next'

export const metadata: Metadata = {
  title: 'Sign in to OpenBank Admin | Operator console',
  description: 'Secure sign-in for authorized OpenBank operators to access the banking operations console.',
}

export default function LoginLayout({ children }: { children: React.ReactNode }) {
  return children
}
