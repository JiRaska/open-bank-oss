// SPDX-License-Identifier: Apache-2.0

const FOCUSABLE = [
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[href]',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

type TabEvent = {
  key: string
  shiftKey: boolean
  preventDefault: () => void
}

/** Keeps sequential keyboard navigation inside a modal dialog. */
export function trapDialogFocus(event: TabEvent, dialog: HTMLElement | null) {
  if (event.key !== 'Tab' || !dialog) return

  const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
    .filter(element => element.getAttribute('aria-hidden') !== 'true')
  if (focusable.length === 0) return

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
