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

// demo modal
const modal = document.getElementById('demo-modal');
if (modal) {
  const open = () => { if (typeof modal.showModal === 'function') modal.showModal(); else modal.setAttribute('open', ''); };
  const close = () => { if (typeof modal.close === 'function') modal.close(); else modal.removeAttribute('open'); };

  document.querySelectorAll('[data-open-demo]').forEach(b => b.addEventListener('click', open));
  document.querySelectorAll('[data-close-demo]').forEach(b => b.addEventListener('click', close));

  // click on backdrop closes
  modal.addEventListener('click', (e) => {
    const card = modal.querySelector('.modal-card');
    const r = card.getBoundingClientRect();
    const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
    if (!inside) close();
  });

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

// subtle parallax on hero logo
const logo = document.querySelector('.hero-logo');
if (logo && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
  window.addEventListener('pointermove', (e) => {
    const x = (e.clientX / window.innerWidth - 0.5) * 14;
    const y = (e.clientY / window.innerHeight - 0.5) * 14;
    logo.style.transform = `translate(${x}px, ${y}px)`;
  });
}
