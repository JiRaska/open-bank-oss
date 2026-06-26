const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/bpmn/page.tsx';
let content = fs.readFileSync(path, 'utf8');

content = content.replace(
  /services: \['aml-service', 'audit-service', 'notification-service'\],\n  \},\n  \{\n    id: 'card-issuance'/,
  `services: ['aml-service', 'audit-service', 'notification-service', 'sanctions-service'],\n  },\n  {\n    id: 'card-issuance'`
);

fs.writeFileSync(path, content);
console.log('Done replacing bpmn/page.tsx again');
