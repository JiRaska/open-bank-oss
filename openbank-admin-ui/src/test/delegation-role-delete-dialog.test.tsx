// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'fs'
import { join } from 'path'
import { DeleteRoleDialog, OwnershipRoleNotice, RoleCatalog, RoleEditor, RoleOverview } from '@/components/delegations/RoleCatalog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { RolePreset } from '@/lib/delegations/rolePresets'

vi.mock('@/lib/auth/useAuth', () => ({
  useAuth: () => ({ hasRole: (role: string) => role === 'ROLE_ADMIN' }),
}))

const ROLE = {
  id: 'treasury-operator',
  name: 'Treasury operator',
  description: 'Can prepare treasury payments',
  resourceType: 'PAYMENT',
  capabilities: ['OBJECT_READ'],
} satisfies RolePreset

const LEGACY_OWNER_ROLE = {
  id: 'legacy-account-owner',
  name: 'Majitel účtu',
  description: 'Legacy full-access preset',
  resourceType: 'ACCOUNT',
  capabilities: ['ACCOUNT_READ_BALANCES', 'DELEGATION_MANAGE'],
} satisfies RolePreset

const LEGACY_CARD_OWNER_ROLE = {
  id: 'legacy-card-owner',
  name: 'Majitel karty',
  description: 'Legacy full-card preset',
  resourceType: 'CARD',
  capabilities: ['CARD_VIEW', 'CARD_VIEW_TRANSACTIONS', 'CARD_MANAGE_LIMITS', 'CARD_MANAGE_STATUS', 'CARD_MANAGE_CHANNELS'],
} satisfies RolePreset

const LEGACY_ONLY_ROLE = {
  ...LEGACY_OWNER_ROLE,
  id: 'legacy-only',
  capabilities: ['DELEGATION_MANAGE'],
} satisfies RolePreset

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('delegation role deletion', () => {
  it('never falls back to a native browser confirmation', () => {
    const source = readFileSync(join(process.cwd(), 'src/components/delegations/RoleCatalog.tsx'), 'utf8')
    expect(source).not.toContain('window.confirm')
  })

  it('explains the security impact and contains keyboard focus', () => {
    const cancel = vi.fn()
    render(
      <LanguageProvider>
        <DeleteRoleDialog role={ROLE} busy={false} failed={false} onCancel={cancel} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog', { name: 'Delete “Treasury operator”?' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText(/Existing grants will not change or be revoked/)).toBeVisible()

    const keep = screen.getByRole('button', { name: 'Keep role' })
    const remove = screen.getByRole('button', { name: 'Delete preset' })
    keep.focus()
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(remove)
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(keep)

    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('prevents duplicate deletion and makes a failed attempt recoverable', () => {
    const confirm = vi.fn()
    const { rerender } = render(
      <LanguageProvider>
        <DeleteRoleDialog role={ROLE} busy failed={false} onCancel={vi.fn()} onConfirm={confirm} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alertdialog')).toHaveAttribute('aria-busy', 'true')
    const deleting = screen.getByRole('button', { name: 'Deleting…' })
    expect(deleting).toBeDisabled()
    fireEvent.click(deleting)
    expect(confirm).not.toHaveBeenCalled()

    rerender(
      <LanguageProvider>
        <DeleteRoleDialog role={ROLE} busy={false} failed onCancel={vi.fn()} onConfirm={confirm} />
      </LanguageProvider>,
    )
    expect(screen.getByRole('alert')).toHaveTextContent('Nothing changed; try again.')
    fireEvent.click(screen.getByRole('button', { name: 'Delete preset' }))
    expect(confirm).toHaveBeenCalledOnce()
  })
})

describe('delegation role editor recovery', () => {
  it('keeps a failed save editable and allows a safe retry', () => {
    const save = vi.fn(async () => undefined)
    render(
      <LanguageProvider>
        <RoleEditor value={ROLE} busy={false} failed cancel={vi.fn()} save={save} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('dialog')).toHaveAttribute('aria-busy', 'false')
    expect(screen.getByRole('alert')).toHaveTextContent('Nothing changed; check the details and try again.')
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Treasury reviewer' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'Treasury reviewer' }))
  })

  it('locks dismissal and duplicate submission while saving, then releases the lock for retry', async () => {
    let releaseFirstResponse!: (response: Response) => void
    const firstResponse = new Promise<Response>(resolve => { releaseFirstResponse = resolve })
    let postRequests = 0
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const method = init?.method ?? 'GET'
      if (method === 'GET') {
        return Promise.resolve(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }))
      }
      if (method === 'POST' && String(input) === '/api/delegation-role-presets') {
        postRequests += 1
        if (postRequests === 1) return firstResponse
        return Promise.resolve(new Response('{}', { status: 201, headers: { 'content-type': 'application/json' } }))
      }
      return Promise.reject(new Error(`unexpected ${method} ${String(input)}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <LanguageProvider>
        <RoleCatalog />
      </LanguageProvider>,
    )

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/delegation-role-presets',
      expect.objectContaining({ cache: 'no-store' }),
    ))
    fireEvent.click(screen.getByRole('button', { name: 'Add role' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Payments reviewer' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Account details' }))

    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeEnabled()
    act(() => {
      save.click()
      save.click()
    })

    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(dialog).toBeInTheDocument()
    expect(postRequests).toBe(1)

    releaseFirstResponse(new Response('{}', { status: 503, headers: { 'content-type': 'application/json' } }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing changed; check the details and try again.')
    const retry = screen.getByRole('button', { name: 'Save' })
    expect(retry).toBeEnabled()
    fireEvent.click(retry)

    await waitFor(() => expect(postRequests).toBe(2))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('does not offer recursive delegation and removes it only on an explicit save', () => {
    const save = vi.fn(async () => undefined)
    render(
      <LanguageProvider>
        <RoleEditor value={LEGACY_OWNER_ROLE} busy={false} failed={false} cancel={vi.fn()} save={save} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('note', { name: 'Unsupported capability' })).toHaveTextContent('Existing grants will not change.')
    expect(screen.queryByRole('checkbox', { name: 'Delegates' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'Plný disponent účtu', capabilities: ['ACCOUNT_READ_BALANCES'] }))
  })

  it('explains how to retire a legacy-only capability instead of promising an impossible save', () => {
    render(
      <LanguageProvider>
        <RoleEditor value={LEGACY_ONLY_ROLE} busy={false} failed={false} cancel={vi.fn()} save={vi.fn()} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('note', { name: 'Unsupported capability' })).toHaveTextContent('Select at least one supported right, or delete the preset.')
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
  })
})

describe('ownership role semantics', () => {
  it('explains that owner roles come from product records rather than presets', () => {
    render(
      <LanguageProvider>
        <OwnershipRoleNotice />
      </LanguageProvider>,
    )

    const note = screen.getByRole('note', { name: 'Ownership roles' })
    expect(note).toHaveTextContent('Ownership is not assignable')
    expect(note).toHaveTextContent('ownership cannot be created or changed here')
  })

  it('keeps an unsupported historical capability visible without presenting it as a right', () => {
    render(
      <LanguageProvider>
        <RoleOverview roles={[LEGACY_OWNER_ROLE]} canManage={false} edit={vi.fn()} remove={vi.fn()} />
      </LanguageProvider>,
    )

    const evidence = screen.getByRole('note', { name: 'Legacy unsupported capabilities' })
    expect(evidence).toHaveTextContent('Historical evidence only — not effective authority')
    expect(evidence).toContainElement(screen.getByTitle('DELEGATION_MANAGE'))
    expect(screen.getByTitle('DELEGATION_MANAGE')).toHaveTextContent('Delegates · not enforced')
    expect(screen.getAllByTitle('DELEGATION_MANAGE')).toHaveLength(1)
  })

  it('presents the seeded card preset as a delegate role while preserving its stored label as evidence', () => {
    render(
      <LanguageProvider>
        <RoleOverview roles={[LEGACY_CARD_OWNER_ROLE]} canManage={false} edit={vi.fn()} remove={vi.fn()} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('heading', { name: 'Plný disponent karty' })).toBeVisible()
    const evidence = screen.getByRole('note', { name: 'Legacy ownership label' })
    expect(evidence).toHaveTextContent('stored legacy label “Majitel karty” described ownership')
    expect(evidence).toHaveTextContent('Existing grants do not change')
  })
})
