const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/api/page.tsx';
let content = fs.readFileSync(path, 'utf8');

content = content.replace(/\\n                \{\/\* Expand toggle \*\/\}/g, '\n                {/* Expand toggle */}');
content = content.replace(/ \}\)\}\\n/g, ' })}\n');

fs.writeFileSync(path, content);
console.log('Fixed literal newlines');
