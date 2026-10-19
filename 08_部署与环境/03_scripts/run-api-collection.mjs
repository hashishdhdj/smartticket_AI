// Execute the repository's current collection with a small, explicit pm compatibility layer.
// This is not Postman/Newman. Unsupported collection features fail rather than silently skip.
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

let raw = '';
for await (const chunk of process.stdin) raw += chunk;
const { collection, variables } = JSON.parse(raw);
const values = new Map(Object.entries(variables));
const replace = value => String(value).replace(/\{\{([^}]+)\}\}/g, (_, key) => {
  if (key === '$guid') return randomUUID();
  assert(values.has(key), `Missing variable ${key}`);
  return String(values.get(key));
});
const rows = [];
for (const item of collection.item) {
  const request = item.request;
  assert(['GET', 'POST', 'PUT'].includes(request.method), 'Unsupported HTTP method');
  assert(!item.item && !request.body?.file, 'Unsupported nested/file request');
  assert((item.event ?? []).every(event => event.listen === 'test'), 'Unsupported script event');
  const target = new URL(replace(request.url));
  assert.equal(target.origin, 'http://127.0.0.1:18083', 'Only isolated local application permitted');
  const headers = Object.fromEntries((request.header ?? []).map(h => [h.key, replace(h.value)]));
  const response = await fetch(target, { method: request.method, headers,
    body: request.body ? replace(request.body.raw) : undefined, redirect: 'error',
    signal: AbortSignal.timeout(75000) });
  const data = await response.json();
  const checks = [];
  const pm = {
    test(name, callback) { callback(); checks.push({ name, status: 'PASS' }); },
    expect(actual) { return { to: { eql(expected) { assert.deepEqual(actual, expected); } } }; },
    response: { json: () => data, to: { have: { status(expected) { assert.equal(response.status, expected); } } } },
    variables: { get: key => values.get(key), replaceIn: replace },
    collectionVariables: { get: key => values.get(key), set: (key, value) => values.set(key, value),
      unset: key => values.delete(key) },
  };
  for (const event of item.event ?? []) {
    assert.equal(event.script.type, 'text/javascript');
    vm.runInNewContext(event.script.exec.join('\n'), { pm }, { timeout: 1000 });
  }
  rows.push({ name: item.name, method: request.method, path: target.pathname,
    http_status: response.status, checks });
}
process.stdout.write(JSON.stringify({ status: 'PASS', runner: 'Node HTTP + limited pm compatibility; not Postman/Newman',
  requests: rows.length, assertions: rows.reduce((n, row) => n + row.checks.length, 0), rows }));
