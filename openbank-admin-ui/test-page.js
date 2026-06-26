const x = `
  /api/v1/accounts:
    get:
`
const paths = x.match(/^  \/[^\n]+/gm)?.map(p => p.trim().replace(/:$/, '')) ?? [];
console.log(paths)
