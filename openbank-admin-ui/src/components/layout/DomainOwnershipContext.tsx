// SPDX-License-Identifier: Apache-2.0
'use client'
import { createContext, useContext } from 'react'
import type { DomainGroup } from '@/lib/governance/domains'

/** Server-reviewed ownership seeds the first paint; no client fetch or permission changes. */
export type DomainOwnership = Record<string, DomainGroup>
const DomainOwnershipContext = createContext<DomainOwnership>({})
export function DomainOwnershipProvider({ ownership, children }: { ownership: DomainOwnership; children: React.ReactNode }) {
  return <DomainOwnershipContext.Provider value={ownership}>{children}</DomainOwnershipContext.Provider>
}
export function useDomainOwnership(): DomainOwnership { return useContext(DomainOwnershipContext) }
