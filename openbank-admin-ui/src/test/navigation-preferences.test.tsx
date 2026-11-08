// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from 'react'
import { InitialRolesProvider } from '@/components/auth/InitialRolesContext'
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useNavigationPreferences } from '@/lib/navigation/preferences'
const state = vi.hoisted(() => ({ id: 'operator-one', loading: false }))
vi.mock('next-auth/react', () => ({ useSession: () => ({ status: state.loading ? 'loading' : 'authenticated', data: state.loading ? null : { user: { id: state.id } } }) }))
beforeEach(() => { localStorage.clear(); state.id = 'operator-one'; state.loading = false })

describe('operator navigation preferences', () => {
  it('shares live updates across shell components and separates operators', () => {
    const first = renderHook(useNavigationPreferences)
    const second = renderHook(useNavigationPreferences)
    act(() => first.result.current.togglePin({ href: '/accounts?status=ACTIVE&view=saved', pathname: '/accounts', label: 'Active accounts' }))
    expect(second.result.current.pins[0].label).toBe('Active accounts')
    act(() => first.result.current.setWidth(320))
    expect(second.result.current.width).toBe(320)
    state.id = 'operator-two'
    second.rerender()
    expect(second.result.current.pins).toEqual([])
    expect(second.result.current.width).toBe(280)
  })
  it('accepts preferences immediately using the server identity while session refresh is pending', () => {
    state.loading = true
    const hook = renderHook(useNavigationPreferences, { wrapper: ({ children }: { children: ReactNode }) =>
      <InitialRolesProvider roles={['ROLE_OPERATOR']} operatorId="server-operator">{children}</InitialRolesProvider> })
    act(() => hook.result.current.selectArea('documentation'))
    expect(hook.result.current.area).toBe('documentation')
    expect(JSON.parse(localStorage.getItem('ob.navigation.v1.server-operator')!).area).toBe('documentation')
    state.id = 'server-operator'
    state.loading = false
    hook.rerender()
    expect(hook.result.current.area).toBe('documentation')
  })
  it('keeps the menu usable when browser storage is unavailable', () => {
    state.id = 'storage-unavailable-operator'
    const failure = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Unavailable') })
    const hook = renderHook(useNavigationPreferences)
    act(() => hook.result.current.toggleCompact())
    expect(hook.result.current.compact).toBe(true)
    act(() => hook.result.current.selectArea('documentation'))
    expect(hook.result.current.area).toBe('documentation')
    failure.mockRestore()
  })
})
