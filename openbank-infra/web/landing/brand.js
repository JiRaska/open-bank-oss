// SPDX-License-Identifier: Apache-2.0
// Play the supplied sequence once; keep its completed wordmark visible afterwards.
const brandFilm = document.querySelector('.brand-film');
if (brandFilm) {
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const updateBrandMotion = () => {
    if (reducedMotion.matches) {
      brandFilm.pause();
      brandFilm.load(); // Restore the completed-logo poster, not an intermediate frame.
    } else {
      brandFilm.play().catch(() => brandFilm.load());
    }
  };
  if (!reducedMotion.matches) updateBrandMotion();
  reducedMotion.addEventListener('change', updateBrandMotion);
}
