// SPDX-License-Identifier: Apache-2.0

/**
 * Runs in <head>, before body paint. Keep this static and value-allowlisted: it
 * is nonce-authorized by the per-request CSP and must never interpolate data.
 */
export const THEME_BOOTSTRAP_SCRIPT = `(function(){try{var t=localStorage.getItem('ob-admin-theme');document.documentElement.classList.toggle('dark',t==='dark')}catch(e){}})()`
