// SPDX-License-Identifier: Apache-2.0
// Progressive enhancement: the complete catalog is visible without JavaScript.
const search = document.getElementById('module-search');
const catalogStatus = document.getElementById('catalog-status');
const groups = [...document.querySelectorAll('.domain-group')];
if (search && catalogStatus) {
  const filter = () => {
    const query = search.value.trim().toLocaleLowerCase();
    let count = 0;
    for (const group of groups) {
      const areaMatches = group.querySelector('h3').textContent.toLocaleLowerCase().includes(query);
      let visible = 0;
      for (const item of group.querySelectorAll('.module-list li')) {
        item.hidden = !areaMatches && !item.textContent.toLocaleLowerCase().includes(query);
        if (!item.hidden) visible++;
      }
      group.hidden = visible === 0;
      count += visible;
    }
    catalogStatus.textContent = `${count} modules ${query ? 'match your search' : 'in the repository snapshot'}.`;
  };
  search.addEventListener('input', filter);
  filter();
}
