const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/service-map/page.tsx';
let content = fs.readFileSync(path, 'utf8');

const correctLabels = `const GROUP_LABELS: Record<string, { label: string; color: string }> = {
  core:       { label: 'Core Banking',    color: '#2563eb' },
  payment:    { label: 'Payments',        color: '#7c3aed' },
  compliance: { label: 'Compliance',      color: '#dc2626' },
  identity:   { label: 'Identity',        color: '#059669' },
  psd2:       { label: 'PSD2 / Open Banking', color: '#d97706' },
  platform:   { label: 'Platform',        color: '#6b7280' },
  cards:      { label: 'Cards',           color: '#db2777' },
}`;

content = content.replace(/const GROUP_LABELS: Record<string, \{ label: string; color: string \}> = \{[\s\S]*?\}\n\}?/, correctLabels);
// let's do it safer:
content = content.replace(/const GROUP_LABELS: Record<string, \{ label: string; color: string \}> = \{[\s\S]*?platform:   \{ label: 'Platform',        color: '#6b7280' \},\n\}/, correctLabels);

fs.writeFileSync(path, content);
