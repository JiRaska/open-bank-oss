const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/service-map/page.tsx';
let content = fs.readFileSync(path, 'utf8');

content = content.replace(/<text x="70" y="618" fontSize="10" fill="#d97706" fontWeight="700">PSD2 \/ OPEN BANKING<\/text>\n            <text x="850" y="58" fontSize="10" fill="#6b7280" fontWeight="700">PLATFORM<\/text>/, '<text x="70" y="618" fontSize="10" fill="#d97706" fontWeight="700">PSD2 / OPEN BANKING</text>');

fs.writeFileSync(path, content);
console.log('Removed duplicate PLATFORM label');
