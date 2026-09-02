// Regenerates portal/server/src/main/resources/i18n-catalog.json from the Node reference
// stack (which serves the authoritative, correctly-encoded seed catalog). One-off repair for
// P9.2 triage: the Java-side copy was double-encoded (UTF-8 read as Latin-1 and re-encoded).
const NODE_BASE = process.env.NODE_BASE ?? 'http://localhost:18084';
const OUT = process.argv[2] ?? 'portal/server/src/main/resources/i18n-catalog.json';

const config = await (await fetch(`${NODE_BASE}/api/i18n/config`)).json();
const labels = {};
for (const lang of config.languages) {
  const bundle = await (await fetch(`${NODE_BASE}/api/i18n/labels/${lang.code}`)).json();
  labels[lang.code] = bundle.labels;
}
const catalog = {
  defaultLanguage: config.defaultLanguage,
  languages: config.languages.map((l) => ({
    code: l.code,
    name: l.name,
    nativeName: l.nativeName,
    sortOrder: l.sortOrder,
  })),
  labels,
};
const { writeFileSync } = await import('node:fs');
writeFileSync(OUT, JSON.stringify(catalog, null, 2) + '\n', 'utf8');
console.log(`written ${OUT}: ${config.languages.length} languages, ${Object.values(labels).map((l) => Object.keys(l).length).join('/')} labels`);
