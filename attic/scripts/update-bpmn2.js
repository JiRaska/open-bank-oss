const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/bpmn/page.tsx';
let content = fs.readFileSync(path, 'utf8');

const cardProcess = `  {
    id: 'card-issuance',
    name: 'Card Issuance',
    desc: 'Proces vydání nové platební karty (fyzické či virtuální)',
    regulation: 'PCI-DSS + PSD2',
    steps: [
      { id: 'start',   type: 'start',   x: 60,  y: 100, label: 'Žádost' },
      { id: 'catalog', type: 'task',    x: 180, y: 100, label: 'Katalog produktů', lane: 'core', apis: ['GET /api/v1/products/{id}'], relatedServices: ['product-catalog-service'] },
      { id: 'gw1',     type: 'gateway', x: 320, y: 100, label: 'Dostupné?' },
      { id: 'reject',  type: 'end-err', x: 320, y: 220, label: 'Zamítnuto' },
      { id: 'issue',   type: 'task',    x: 460, y: 100, label: 'Vytvoření karty', lane: 'cards', apis: ['POST /api/v1/cards'], relatedServices: ['card-issuance-service'] },
      { id: 'notify',  type: 'task',    x: 600, y: 100, label: 'Notifikace PINu', lane: 'platform', apis: ['POST /api/v1/notifications'], relatedServices: ['notification-service'] },
      { id: 'end',     type: 'end',     x: 740, y: 100, label: 'Vydáno' },
    ],
    flows: [
      { from: 'start', to: 'catalog' },
      { from: 'catalog', to: 'gw1' },
      { from: 'gw1', to: 'reject', label: 'Ne' },
      { from: 'gw1', to: 'issue', label: 'Ano' },
      { from: 'issue', to: 'notify' },
      { from: 'notify', to: 'end' },
    ],
    services: ['product-catalog-service', 'card-issuance-service', 'notification-service'],
  },
]`;

content = content.replace(/services: \['aml-service', 'audit-service', 'notification-service'\],\n  \},\n\]/, "services: ['aml-service', 'audit-service', 'notification-service'],\n  },\n" + cardProcess);

fs.writeFileSync(path, content);
console.log('Done replacing bpmn/page.tsx');
