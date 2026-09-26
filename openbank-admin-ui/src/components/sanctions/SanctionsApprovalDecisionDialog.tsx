// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

'use client'

import { useRef } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { AlertTriangle } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

export interface PendingApprovalItem {
  id: string
  action: string
  resourceId?: string | null
  status: string
  makerId?: string | null
  createdAt?: string | null
}

export interface ApprovalDecisionIntent {
  approval: PendingApprovalItem
  approve: boolean
}

export function SanctionsApprovalDecisionDialog({ intent, busy, message, onCancel, onConfirm }: {
  intent: ApprovalDecisionIntent
  busy: boolean
  message: string
  onCancel: () => void
  onConfirm: () => Promise<void>
}) {
  const { t } = useLanguage()
  const cancelRef = useRef<HTMLButtonElement>(null)
  const action = intent.approve ? t('Schválit žádost', 'Approve request') : t('Zamítnout žádost', 'Reject request')

  return <Dialog.Root open onOpenChange={open => { if (!open && !busy) onCancel() }}>
    <Dialog.Portal>
      <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 1200, background: 'rgba(15,23,42,.68)' }} />
      <Dialog.Content
        role="alertdialog"
        aria-modal="true"
        aria-busy={busy}
        onOpenAutoFocus={event => {
          event.preventDefault()
          cancelRef.current?.focus()
        }}
        onEscapeKeyDown={event => { if (busy) event.preventDefault() }}
        onInteractOutside={event => event.preventDefault()}
        className="card"
        style={{ position: 'fixed', zIndex: 1201, top: '50%', left: '50%', transform: 'translate(-50%, -50%)', width: 'calc(100% - 40px)', maxWidth: 560, maxHeight: 'calc(100dvh - 40px)', overflowY: 'auto', padding: 22 }}
      >
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
          <AlertTriangle aria-hidden="true" size={19} style={{ color: intent.approve ? 'var(--warning)' : 'var(--danger)', flexShrink: 0, marginTop: 2 }} />
          <div>
            <Dialog.Title style={{ margin: 0, fontSize: 17, fontWeight: 750 }}>{action}</Dialog.Title>
            <Dialog.Description style={{ margin: '6px 0 0', fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-secondary)' }}>
              {intent.approve
                ? t('Potvrdíte rozhodnutí jiného operátora. Maker pak může znovu odeslat řízenou sankční dispozici; toto schválení ji samo neprovede.', 'You are confirming another operator’s decision. The maker may then retry the governed sanctions disposition; this approval does not execute it.')
                : t('Žádost odmítnete. Maker toto schválení nemůže použít a sankční dispozice se neprovede.', 'You are refusing the request. The maker cannot use this approval and the sanctions disposition will not execute.')}
            </Dialog.Description>
          </div>
        </div>
        <div style={{ marginTop: 14, padding: '11px 12px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)', fontSize: 12.5 }}>
          <div><strong>{t('Akce', 'Action')}:</strong> {intent.approval.action}</div>
          <div style={{ marginTop: 5 }}><strong>{t('Požádal', 'Requested by')}:</strong> {intent.approval.makerId ?? t('neuvedeno', 'not provided')}</div>
          <div style={{ marginTop: 5, fontFamily: 'var(--font-mono)', wordBreak: 'break-all' }}><strong>{t('ID žádosti', 'Approval ID')}:</strong> {intent.approval.id}</div>
        </div>
        {message && <p role="alert" style={{ margin: '12px 0 0', padding: '10px 12px', borderRadius: 8, color: 'var(--danger-text)', background: 'var(--danger-bg)', border: '1px solid var(--danger-border)', fontSize: 12 }}>{message}</p>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <button ref={cancelRef} type="button" className="btn btn-secondary" disabled={busy} onClick={onCancel}>{t('Zpět ke kontrole', 'Back to review')}</button>
          <button type="button" className={intent.approve ? 'btn btn-primary' : 'btn btn-danger'} disabled={busy} aria-busy={busy} onClick={() => void onConfirm()}>
            {busy ? t('Ukládám rozhodnutí…', 'Recording decision…') : intent.approve ? t('Potvrdit schválení', 'Confirm approval') : t('Potvrdit zamítnutí', 'Confirm rejection')}
          </button>
        </div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>
}
