// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { Header } from '@/components/layout/Header'
import { Sidebar } from '@/components/layout/Sidebar'

export default function Layout({ children }: { children: React.ReactNode }) {
  return <div style={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
    <Sidebar />
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Header />
      <main style={{ flex: 1, overflowY: 'auto', padding: 24, background: 'var(--bg)' }}>{children}</main>
    </div>
  </div>
}
