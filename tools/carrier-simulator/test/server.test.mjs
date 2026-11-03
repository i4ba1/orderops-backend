import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createCarrierServer } from '../server.mjs';

async function fixture(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'orderops-carrier-'));
  const dataFile = join(directory, 'state.json');
  const running = [];
  async function start() {
    const server = await createCarrierServer({ dataFile, ...options });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    running.push(server);
    return { server, url: `http://127.0.0.1:${server.address().port}` };
  }
  t.after(async () => {
    for (const server of running) {
      if (server.listening) {
        server.closeAllConnections();
        await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
      }
    }
    await rm(dataFile, { force: true });
    await rm(`${dataFile}.tmp`, { force: true });
    await rmdir(directory);
  });
  return { start, dataFile };
}

function request(orderId, demoScenario = 'NORMAL') {
  return {
    orderId,
    demoScenario,
    shippingAddress: { recipient: 'Ada Lovelace', line1: '10 Example Street', city: 'London', postalCode: 'SW1A 1AA', country: 'GB' },
  };
}

function shipment(url, body, signal) {
  return fetch(`${url}/shipments`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'Idempotency-Key': body.orderId },
    body: JSON.stringify(body),
    signal,
  });
}

test('a shipment survives a restart and the same key never creates a second shipment', async t => {
  const { start } = await fixture(t);
  const first = await start();
  const body = request(randomUUID());
  const created = await shipment(first.url, body);
  assert.equal(created.status, 201);
  const original = await created.json();
  first.server.closeAllConnections();
  await new Promise(resolve => first.server.close(resolve));
  const restarted = await start();
  const replay = await shipment(restarted.url, body);
  assert.equal(replay.status, 200);
  assert.deepEqual(await replay.json(), original);
  const stats = await fetch(`${restarted.url}/internal/stats`).then(response => response.json());
  assert.equal(stats.shipments, 1);
  assert.equal(stats.attempts, 2);
});

test('three carrier failures exhaust automatic retries and the next recovery attempt succeeds once', async t => {
  const { start } = await fixture(t);
  const { url } = await start();
  const body = request(randomUUID(), 'FAIL_THEN_RECOVER');
  for (let attempt = 0; attempt < 3; attempt++) {
    const failure = await shipment(url, body);
    assert.equal(failure.status, 503);
    await failure.json();
  }
  const failedStats = await fetch(`${url}/internal/stats`).then(response => response.json());
  assert.equal(failedStats.shipments, 0);
  assert.equal(failedStats.attempts, 3);
  const recovered = await shipment(url, body);
  assert.equal(recovered.status, 201);
  const original = await recovered.json();
  const replayed = await shipment(url, body);
  assert.equal(replayed.status, 200);
  assert.deepEqual(await replayed.json(), original);
});

test('a lost response after carrier creation is recovered with one shipment', async t => {
  const { start } = await fixture(t, { timeoutDelayMs: 180 });
  const { url } = await start();
  await fetch(`${url}/health`).then(response => response.json());
  const body = request(randomUUID(), 'TIMEOUT_AFTER_CREATE');
  await assert.rejects(shipment(url, body, AbortSignal.timeout(60)), { name: 'TimeoutError' });
  const recovered = await shipment(url, body);
  assert.equal(recovered.status, 200);
  assert.ok((await recovered.json()).shipmentId);
  const stats = await fetch(`${url}/internal/stats`).then(response => response.json());
  assert.equal(stats.shipments, 1);
  assert.equal(stats.attempts, 2);
});

test('concurrent requests for one key create exactly one shipment', async t => {
  const { start } = await fixture(t);
  const { url } = await start();
  const body = request(randomUUID());
  const responses = await Promise.all(Array.from({ length: 12 }, () => shipment(url, body)));
  assert.equal(responses.filter(response => response.status === 201).length, 1);
  const values = await Promise.all(responses.map(response => response.json()));
  assert.equal(new Set(values.map(value => value.shipmentId)).size, 1);
  const stats = await fetch(`${url}/internal/stats`).then(response => response.json());
  assert.equal(stats.shipments, 1);
  assert.equal(stats.attempts, 12);
});

test('reusing a key with different shipping details is rejected', async t => {
  const { start } = await fixture(t);
  const { url } = await start();
  const body = request(randomUUID());
  assert.equal((await shipment(url, body)).status, 201);
  const changed = { ...body, shippingAddress: { ...body.shippingAddress, city: 'Manchester' } };
  assert.equal((await shipment(url, changed)).status, 409);
  assert.equal((await shipment(url, body)).status, 200);
  const stats = await fetch(`${url}/internal/stats`).then(response => response.json());
  assert.equal(stats.shipments, 1);
});

test('invalid address data cannot create carrier state', async t => {
  const { start } = await fixture(t);
  const { url } = await start();
  const body = request(randomUUID());
  body.shippingAddress.country = 'invalid';
  assert.equal((await shipment(url, body)).status, 400);
  const stats = await fetch(`${url}/internal/stats`).then(response => response.json());
  assert.equal(stats.shipments, 0);
  assert.equal(stats.attempts, 0);
});

test('an unused carrier has a durable empty state that can be backed up', async t => {
  const { start, dataFile } = await fixture(t);
  await start();
  const stored = JSON.parse(await readFile(dataFile, 'utf8'));
  assert.deepEqual(stored.records, {});
});
