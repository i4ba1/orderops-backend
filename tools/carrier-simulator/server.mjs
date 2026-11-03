import http from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const scenarios = new Set(['NORMAL', 'FAIL_THEN_RECOVER', 'TIMEOUT_AFTER_CREATE']);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function problem(status, message) {
  return Object.assign(new Error(message), { status });
}

function json(response, status, value, headers = {}) {
  if (response.destroyed) return;
  response.writeHead(status, { 'content-type': 'application/json', ...headers });
  response.end(JSON.stringify(value));
}

async function bodyJson(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > 65536) throw problem(413, 'Request body exceeds 64 KiB');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw problem(400, 'A JSON request body is required'); }
}

function validated(body, key) {
  if (!body || typeof body !== 'object' || !uuid.test(key ?? '') || body.orderId !== key) {
    throw problem(400, 'Idempotency-Key must equal the order UUID');
  }
  const source = body.shippingAddress;
  if (!source || typeof source !== 'object') throw problem(400, 'Shipping address is required');
  const shippingAddress = {};
  for (const [field, maximum] of [['recipient', 120], ['line1', 200], ['city', 100], ['postalCode', 32], ['country', 2]]) {
    const value = source[field];
    if (typeof value !== 'string' || !value.trim() || value.length > maximum) {
      throw problem(400, `Invalid shipping address field: ${field}`);
    }
    shippingAddress[field] = value;
  }
  if (!/^[A-Z]{2}$/.test(shippingAddress.country)) throw problem(400, 'Country must be a two-letter uppercase code');
  const demoScenario = body.demoScenario ?? 'NORMAL';
  if (!scenarios.has(demoScenario)) throw problem(400, 'Unknown demonstration scenario');
  return { orderId: body.orderId, shippingAddress, demoScenario };
}

export async function createCarrierServer({ dataFile, timeoutDelayMs = 3000, failuresBeforeRecovery = 3 } = {}) {
  if (!dataFile) throw new Error('A persistent carrier data file is required');
  let state;
  let initialize = false;
  try {
    state = JSON.parse(await readFile(dataFile, 'utf8'));
    if (!state || typeof state.records !== 'object' || state.records === null) throw new Error('Invalid carrier state');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    state = { records: {} };
    initialize = true;
  }
  await mkdir(dirname(dataFile), { recursive: true });
  if (initialize) await writeFile(dataFile, JSON.stringify(state), { mode: 0o600, flag: 'wx' });
  let queue = Promise.resolve();
  function serialized(action) {
    const next = queue.then(action);
    queue = next.catch(() => {});
    return next;
  }
  async function persist() {
    await writeFile(`${dataFile}.tmp`, JSON.stringify(state), { mode: 0o600 });
    await rename(`${dataFile}.tmp`, dataFile);
  }

  return http.createServer(async (request, response) => {
    try {
      if (request.method === 'GET' && request.url === '/health') {
        return json(response, 200, { status: 'UP', service: 'carrier-simulator' });
      }
      if (request.method === 'GET' && request.url === '/internal/stats') {
        await queue;
        const records = Object.values(state.records);
        return json(response, 200, {
          shipments: records.filter(record => record.shipment).length,
          attempts: records.reduce((sum, record) => sum + record.attempts, 0),
        });
      }
      if (request.method !== 'POST' || request.url !== '/shipments') return json(response, 404, { message: 'Route not found' });
      const key = request.headers['idempotency-key'];
      const body = validated(await bodyJson(request), key);
      const fingerprint = createHash('sha256').update(JSON.stringify(body)).digest('hex');
      const result = await serialized(async () => {
        const existing = state.records[key];
        if (existing && existing.fingerprint !== fingerprint) throw problem(409, 'Key already belongs to a different shipment request');
        const record = existing ?? { fingerprint, attempts: 0, shipment: null };
        record.attempts += 1;
        state.records[key] = record;
        if (body.demoScenario === 'FAIL_THEN_RECOVER' && record.attempts <= failuresBeforeRecovery) {
          await persist();
          return { status: 503, shipment: { message: 'Simulated carrier outage; retry is safe' } };
        }
        const created = !record.shipment;
        if (created) {
          record.shipment = {
            shipmentId: randomUUID(),
            trackingNumber: `OO${key.replaceAll('-', '').slice(0, 16).toUpperCase()}`,
            createdAt: new Date().toISOString(),
          };
        }
        await persist();
        return {
          status: created ? 201 : 200,
          shipment: record.shipment,
          delayResponse: created && body.demoScenario === 'TIMEOUT_AFTER_CREATE',
        };
      });
      if (result.delayResponse) {
        await new Promise(resolveDelay => setTimeout(resolveDelay, timeoutDelayMs).unref());
      }
      return json(response, result.status, result.shipment);
    } catch (error) {
      return json(response, error.status ?? 500, {
        message: error.status ? error.message : 'Carrier storage failure',
      });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = await createCarrierServer({
    dataFile: resolve(process.env.CARRIER_DATA_FILE ?? '.data/carrier-state.json'),
    timeoutDelayMs: Number(process.env.CARRIER_TIMEOUT_DELAY_MS ?? 3000),
  });
  const port = Number(process.env.PORT ?? 8090);
  server.listen(port, process.env.HOST ?? '127.0.0.1', () => console.log(`Carrier simulator listening on port ${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
