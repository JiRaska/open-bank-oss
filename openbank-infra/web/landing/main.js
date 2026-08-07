// year
document.getElementById('year').textContent = new Date().getFullYear();

// scroll reveal
const io = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (e.isIntersecting) {
      e.target.classList.add('in');
      io.unobserve(e.target);
    }
  }
}, { threshold: 0.14, rootMargin: '0px 0px -40px 0px' });

document.querySelectorAll('.reveal').forEach((el, i) => {
  el.style.transitionDelay = `${Math.min(i % 6, 5) * 70}ms`;
  io.observe(el);
});

// modals (demo + testflight) — shared open/close/backdrop wiring
function wireModal(id, openSel, closeSel) {
  const m = document.getElementById(id);
  if (!m) return null;
  const open = () => { if (typeof m.showModal === 'function') m.showModal(); else m.setAttribute('open', ''); };
  const close = () => { if (typeof m.close === 'function') m.close(); else m.removeAttribute('open'); };
  document.querySelectorAll(openSel).forEach(b => b.addEventListener('click', (e) => { e.preventDefault(); open(); }));
  document.querySelectorAll(closeSel).forEach(b => b.addEventListener('click', close));
  m.addEventListener('click', (e) => {
    const card = m.querySelector('.modal-card');
    const r = card.getBoundingClientRect();
    const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
    if (!inside) close();
  });
  return m;
}

wireModal('tf-modal', '[data-open-tf]', '[data-close-tf]');

// demo modal
const modal = wireModal('demo-modal', '[data-open-demo]', '[data-close-demo]');
if (modal) {
  // copy-to-clipboard
  modal.querySelectorAll('.copy').forEach(btn => {
    btn.addEventListener('click', async () => {
      const text = btn.getAttribute('data-copy');
      try {
        await navigator.clipboard.writeText(text);
      } catch {
        const t = document.createElement('textarea');
        t.value = text; document.body.appendChild(t); t.select();
        document.execCommand('copy'); t.remove();
      }
      const old = btn.textContent;
      btn.textContent = 'Copied';
      btn.classList.add('copied');
      setTimeout(() => { btn.textContent = old; btn.classList.remove('copied'); }, 1400);
    });
  });
}

// hCaptcha widget loader.
// Replaces the third-party https://web3forms.com/client/script.js, which was an
// unversioned remote script with no SRI (see the SRI finding): a compromise there
// would have run attacker code with full access to this form. Of everything that
// script did (uploadcare/filepond uploaders we never use) only this remains — inject
// hCaptcha's api.js with Web3Forms' public sitekey, exactly as it did.
const HCAPTCHA_SITEKEY = '50b2fe65-b00b-4b9e-ad62-3ba471098be2';
function loadHcaptcha() {
  const widgets = document.querySelectorAll('.h-captcha');
  if (!widgets.length || document.querySelector('script[src*="js.hcaptcha.com"]')) return;
  widgets.forEach((w) => { if (!w.dataset.sitekey) w.dataset.sitekey = HCAPTCHA_SITEKEY; });
  const s = document.createElement('script');
  s.src = 'https://js.hcaptcha.com/1/api.js?recaptchacompat=off';
  s.async = true;
  s.defer = true;
  document.body.appendChild(s);
}
loadHcaptcha();
// bfcache restore: the widget is gone but our guard script tag may still be in the DOM.
window.addEventListener('pageshow', (e) => {
  if (!e.persisted) return;
  if (window.hcaptcha) { try { window.hcaptcha.reset(); } catch (_) {} } else { loadHcaptcha(); }
});

// testflight signup form (Web3Forms, AJAX submit)
const tfForm = document.getElementById('tf-form');
if (tfForm) {
  const status = document.getElementById('tf-status');
  const submitBtn = tfForm.querySelector('.tf-submit');
  tfForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    // hCaptcha gate: only enforce when the widget actually rendered.
    // If it was blocked (CSP, ad-blocker, hCaptcha down) we fail open rather than
    // locking everyone out — the honeypot and Web3Forms' server-side checks still apply.
    const widget = tfForm.querySelector('.h-captcha');
    const rendered = widget && widget.querySelector('iframe');
    const captcha = tfForm.querySelector('[name="h-captcha-response"]');
    if (rendered && (!captcha || !captcha.value)) {
      status.className = 'tf-status err';
      status.textContent = 'Please complete the "I\'m not a robot" check.';
      return;
    }
    status.className = 'tf-status sending';
    status.textContent = 'Sending…';
    submitBtn.disabled = true;
    try {
      const res = await fetch(tfForm.action, {
        method: 'POST',
        headers: { 'Accept': 'application/json' },
        body: new FormData(tfForm),
      });
      const data = await res.json().catch(() => ({}));
      if (res.ok && data.success) {
        tfForm.reset();
        if (window.hcaptcha) { try { window.hcaptcha.reset(); } catch (_) {} }
        status.className = 'tf-status ok';
        status.textContent = "Thanks! We'll send your TestFlight invite soon.";
      } else {
        status.className = 'tf-status err';
        status.textContent = (data && data.message) ? data.message : 'Something went wrong — email hello@open-bank.tech.';
        submitBtn.disabled = false;
      }
    } catch (err) {
      status.className = 'tf-status err';
      status.textContent = 'Network error — email hello@open-bank.tech.';
      submitBtn.disabled = false;
    }
  });
}

// subtle parallax on hero logo
const logo = document.querySelector('.hero-logo');
if (logo && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
  window.addEventListener('pointermove', (e) => {
    const x = (e.clientX / window.innerWidth - 0.5) * 14;
    const y = (e.clientY / window.innerHeight - 0.5) * 14;
    logo.style.transform = `translate(${x}px, ${y}px)`;
  });
}
