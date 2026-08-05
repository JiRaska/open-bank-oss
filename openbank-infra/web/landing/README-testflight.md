# TestFlight beta signup — native form (Web3Forms)

The iOS TestFlight signup is a **native, dark-themed HTML form** in the `#app` section of
`index.html`. No iframe, no third-party badge, no tracking cookies. Submissions are handled by
**Web3Forms** (free tier: 250 submissions/month, no branding).

## Setup — DONE

The access key is set in `index.html` (`#tf-form` → `access_key`) and verified working end-to-end
(Web3Forms returned `success: true`). **Submissions currently go to `jiri@iraska.cz`** — the key was
generated from that address, not hello@open-bank.tech. To move delivery to hello@open-bank.tech,
generate a new key from that inbox at https://web3forms.com and swap it in.

> The Web3Forms access key is **not a secret** — it is meant to sit in client-side HTML. It only
> identifies which inbox submissions go to. (Contrast with the Tally *API* key, which was a real
> secret and was deleted.) Safe to commit.

## What it collects (and nothing else)

- **Apple ID email** — `type=email`, required, browser-validated.
- **Consent checkbox** — required, GDPR Art. 6(1)(a); full consent text inline.
- **Honeypot** (`botcheck`) — hidden anti-spam field. Web3Forms also runs server-side spam checks.

## How it works

- `index.html` — `#tf-form` posts to `https://api.web3forms.com/submit`.
- `main.js` — intercepts submit, sends via `fetch` (AJAX), shows an inline success/error status,
  no page reload. On success the form resets and confirms; on failure it points to hello@open-bank.tech.
- `styles.css` — `.tf-form` and friends: dark inputs, cyan focus ring, styled consent + status.
- `#tf-modal` — "what exactly we collect" privacy detail, opened from the fine-print link.

## Spam protection

- **Honeypot** (`botcheck`) — always on.
- **hCaptcha** — DONE. Widget `<div class="h-captcha" data-sitekey="50b2fe65-…" data-theme="dark">`
  in `#tf-form`, rendered by `loadHcaptcha()` in our own `main.js`, which injects
  `https://js.hcaptcha.com/1/api.js?recaptchacompat=off` with Web3Forms' shared free sitekey
  `50b2fe65-b00b-4b9e-ad62-3ba471098be2`.
  - We used to load `https://web3forms.com/client/script.js` for this. It was an **unversioned
    third-party script with no SRI** — a compromise of that origin would have run attacker code
    inside this form (the polyfill.io class of supply-chain attack), and an `integrity` hash on an
    unversioned URL just swaps that risk for a silently broken form on the vendor's next release.
    Its only other features (uploadcare / filepond file uploads) are unused here, so it is gone and
    `web3forms.com` is out of `script-src`. `api.web3forms.com` still receives the POST.
  `main.js` blocks submit
  until the challenge is solved and resets the widget after success. The token rides along in the
  FormData as `h-captcha-response`.
  - ⚠️ On `localhost` the widget shows "Warning: localhost detected" — that's the shared sitekey's
    dev behaviour and disappears on the real `open-bank.tech` domain.
- **Domain restriction:** configure allowed domains (`open-bank.tech`) in the Web3Forms dashboard →
  the access key's settings. (Dashboard toggle, not a code change; may require a paid plan — check
  your dashboard.)
- **Custom redirect / autoresponder:** configurable in the Web3Forms dashboard.

## ⚠️ Hosting CSP — required allowlist

The form talks to third-party origins, so the site's `Content-Security-Policy` **must** allow them.
The production host (S3 + CloudFront) originally shipped `script-src 'self'; connect-src 'self';
form-action 'self'`, which silently broke both the hCaptcha widget *and* the form submission.

Required policy (set it in the CloudFront response-headers policy):

```
default-src 'self';
script-src 'self' https://js.hcaptcha.com https://*.hcaptcha.com;
style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://*.hcaptcha.com;
font-src 'self' https://fonts.gstatic.com;
img-src 'self' data:;
connect-src 'self' https://api.web3forms.com https://*.hcaptcha.com;
frame-src https://hcaptcha.com https://*.hcaptcha.com;
form-action 'self' https://api.web3forms.com;
base-uri 'self'; frame-ancestors 'none'; upgrade-insecure-requests
```

Symptoms if it's missing: the captcha area is blank and "Please complete the check" never clears
(script blocked), or the captcha works but submitting fails (connect-src blocked).

`main.js` fails **open** when the widget did not render (CSP, ad-blocker, hCaptcha outage) so a
blocked captcha can never lock users out of signing up.

## Data retention

Consent promises deletion on request and when the beta ends. Web3Forms delivers to your inbox;
manage retention there.

## Leftover

The earlier Tally form (`44DJJb`) is no longer used and can be deleted in the Tally dashboard.
