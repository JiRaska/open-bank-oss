// ESLint 9 flat config. eslint-config-next >=15 ships a native flat-config
// array; we extend its core-web-vitals preset (which already includes the
// TypeScript + react-hooks rules) and add project-local ignores.
import nextCoreWebVitals from 'eslint-config-next/core-web-vitals'

const eslintConfig = [
  {
    ignores: ['.next/**', 'node_modules/**', 'coverage/**', 'next-env.d.ts'],
  },
  ...nextCoreWebVitals,
  {
    // Scoped to the file types eslint-config-next defines the react-hooks plugin for. Without
    // `files` this block applies to EVERY linted file, including plain CommonJS at the package
    // root (otel-bootstrap.cjs, otel-scrub.cjs) — and for a file the Next preset does not match,
    // the plugin is undefined while the rule reference remains, so eslint aborts the whole run
    // with "could not find plugin react-hooks". That is a config-resolution error, not a lint
    // finding: it takes down `npm run lint` entirely, including for every pre-existing file.
    files: ['**/*.{js,jsx,mjs,ts,tsx}'],
    // eslint-plugin-react-hooks@7 (pulled in by eslint-config-next 16) adds
    // React-Compiler-era rules that flag long-standing, working patterns in
    // this codebase (fetch-on-mount setState, components declared in render).
    // We keep them visible as warnings rather than silencing them; clearing
    // this debt is tracked as a dedicated follow-up so the framework-coherence
    // upgrade (React 19 + eslint-config-next 16) stays focused and low-risk.
    rules: {
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/static-components': 'warn',
      'react-hooks/purity': 'warn',
      'react-hooks/exhaustive-deps': 'warn',
    },
  },
]

export default eslintConfig
