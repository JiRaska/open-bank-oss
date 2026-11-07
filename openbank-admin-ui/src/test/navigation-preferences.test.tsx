// SPDX-License-Identifier: Apache-2.0
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useNavigationPreferences } from '@/lib/navigation/preferences'
const state = vi.hoisted(() => ({ id: 'operator-one' }))
vi.mock('next-auth/react', () => ({ useSession: () => ({ data: { user: { id: state.id } } }) }))
beforeEach(() => { localStorage.clear(); state.id = 'operator-one' })

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
