// SPDX-License-Identifier: Apache-2.0
// Redoc creates its schema selector after the initial HTML load and supplies no label.
const reference = document.querySelector('redoc')
const labelSelectors = () => {
  for (const selector of reference.querySelectorAll('select:not([aria-label])')) {
    selector.setAttribute('aria-label', 'Request body schema')
  }
}
labelSelectors()
new MutationObserver(labelSelectors).observe(reference, { childList: true, subtree: true })
