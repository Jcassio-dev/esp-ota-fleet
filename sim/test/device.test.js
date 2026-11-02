// O aparelho simulado contra um servidor falso em memória, que implementa só o essencial.

import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { createServer } from 'node:http';
import { after, before, test } from 'node:test';
import { sha256Hex, signFirmware } from '../../cli/src/crypto.js';
import { buildFakeImage } from '../../cli/src/image.js';
import { SimDevice } from '../device.js';

const { privateKey, publicKey } = generateKeyPairSync('ec', {
  namedCurve: 'prime256v1',
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});

let server;
let base;
let offer;
let image;
let events;
let rangeRequests;

function setRelease(version, { tamper = false, offeredVersion = version } = {}) {
  image = buildFakeImage({ version, size: 50_000 });
  offer = {
    releaseId: 1, version: offeredVersion, size: image.length, sha256: sha256Hex(image),
    signature: signFirmware(image, privateKey), url: '/fw', chunkSize: 16384,
  };
  if (tamper) image = Buffer.from(image).fill(0xff, 1000, 1010);
  events = [];
  rangeRequests = 0;
}

before(async () => {
  server = createServer(async (req, res) => {
    let body = '';
    for await (const c of req) body += c;
    if (req.url.endsWith('/checkin')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ update: offer }));
    }
    if (req.url.endsWith('/events')) {
      events.push(JSON.parse(body).type);
      res.writeHead(204);
      return res.end();
    }
    if (req.url === '/fw') {
      rangeRequests++;
      const [, start, end] = /bytes=(\d+)-(\d+)/.exec(req.headers.range);
      res.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${image.length}` });
      return res.end(image.subarray(Number(start), Number(end) + 1));
    }
    res.writeHead(404);
    res.end();
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

test('baixa em blocos, verifica e confirma', async () => {
  setRelease('1.1.0');
  const device = new SimDevice({ id: 'd1', server: base, publicKey, random: () => 0.99 });
  const result = await device.cycle();
  assert.equal(result.outcome, 'confirmed');
  assert.equal(device.version, '1.1.0');
  assert.equal(rangeRequests, Math.ceil(50_000 / 16384));
  assert.deepEqual(events, ['DOWNLOADING', 'INSTALLED', 'CONFIRMED']);
});

test('reverte quando o autoteste falha', async () => {
  setRelease('1.1.0');
  const device = new SimDevice({ id: 'd2', server: base, publicKey, rollbackRate: 1, random: () => 0.5 });
  assert.equal((await device.cycle()).outcome, 'rolled_back');
  assert.equal(device.version, '1.0.0');
  assert.deepEqual(events, ['DOWNLOADING', 'INSTALLED', 'ROLLED_BACK']);
});

test('recusa imagem adulterada', async () => {
  setRelease('1.1.0', { tamper: true });
  const device = new SimDevice({ id: 'd3', server: base, publicKey, random: () => 0.99 });
  const result = await device.cycle();
  assert.equal(result.outcome, 'failed');
  assert.match(result.detail, /SHA-256/);
  assert.deepEqual(events, ['DOWNLOADING', 'FAILED']);
});

test('recusa imagem cuja versão interna difere da oferta', async () => {
  setRelease('1.1.0', { offeredVersion: '1.5.0' });
  const device = new SimDevice({ id: 'd4', server: base, random: () => 0.99 });
  const result = await device.cycle();
  assert.equal(result.outcome, 'failed');
  assert.match(result.detail, /imagem traz 1\.1\.0/);
});

test('recusa downgrade', async () => {
  setRelease('0.9.0');
  const device = new SimDevice({ id: 'd5', server: base, random: () => 0.99 });
  assert.match((await device.cycle()).detail, /downgrade/);
});
