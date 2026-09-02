// One-off: bring the genportal dev realm's role inventory up to the documented portal role set
// (settings/registry/i18n/navigation/ai-hub edit) and grant the missing ones to the dev user,
// so both stacks' dev tenant admins have the same roles (contract-diff env prerequisite).
const KC = 'http://localhost:18080';
const REALM = 'dev';
const ROLES = [
  { name: 'portal-ai-hub-edit', description: 'Can manage AI hub providers, tokens, and channels' },
  { name: 'portal-navigation-edit', description: 'Can manage portal navigation' },
];
const USERNAME = 'dev';

const tokenRes = await fetch(`${KC}/realms/master/protocol/openid-connect/token`, {
  method: 'POST',
  headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    username: 'admin',
    password: process.argv[2],
    grant_type: 'password',
    client_id: 'admin-cli',
  }),
});
const { access_token } = await tokenRes.json();
if (!access_token) throw new Error('admin login failed');
const headers = { authorization: `Bearer ${access_token}`, 'content-type': 'application/json' };

for (const role of ROLES) {
  const exists = await fetch(`${KC}/admin/realms/${REALM}/roles/${encodeURIComponent(role.name)}`, { headers });
  if (exists.status === 404) {
    const created = await fetch(`${KC}/admin/realms/${REALM}/roles`, {
      method: 'POST',
      headers,
      body: JSON.stringify(role),
    });
    console.log(`${created.ok || created.status === 409 ? 'created' : 'FAILED'} role ${role.name} (${created.status})`);
  }
}

const users = await (await fetch(`${KC}/admin/realms/${REALM}/users?username=${USERNAME}&exact=true`, { headers })).json();
if (!users.length) throw new Error(`user ${USERNAME} not found`);
const userId = users[0].id;

const current = await (await fetch(`${KC}/admin/realms/${REALM}/users/${userId}/role-mappings/realm`, { headers })).json();
const have = new Set(current.map((r) => r.name));
const allRoles = await (await fetch(`${KC}/admin/realms/${REALM}/roles`, { headers })).json();
const toAdd = allRoles.filter((r) => ROLES.some((x) => x.name === r.name) && !have.has(r.name));
if (!toAdd.length) {
  console.log('user already has all roles:', [...have].join(', '));
} else {
  const res = await fetch(`${KC}/admin/realms/${REALM}/users/${userId}/role-mappings/realm`, {
    method: 'POST',
    headers,
    body: JSON.stringify(toAdd.map(({ id, name }) => ({ id, name }))),
  });
  console.log(res.ok ? `granted to ${USERNAME}: ${toAdd.map((r) => r.name).join(', ')}` : `grant failed: ${res.status}`);
}
