// Testa os comandos contra um servidor HTTP falso que imita as rotas de admin.

import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, test } from 'node:test';
import { run } from '../src/cli.js';
import { sha256Hex, verifyFirmware } from '../src/crypto.js';
import { buildFakeImage } from '../src/image.js';

const TOKEN = 'token-teste';
let server;
let baseUrl;
const received = [];

before(async () => {
  server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    received.push({ method: req.method, url: req.url, headers: req.headers, body });

    const reply = (status, data, type = 'application/json') => {
      res.writeHead(status, { 'Content-Type': type });
      res.end(data === undefined ? '' : JSON.stringify(data));
    };

    if (req.headers['x-admin-token'] !== TOKEN) {
      return reply(401, { status: 401, detail: 'Token de admin ausente ou inválido' }, 'application/problem+json');
    }
    if (req.method === 'POST' && req.url === '/api/admin/releases') {
      return reply(201, { id: 7, version: '1.1.0', rolloutPercent: 5, status: 'ACTIVE' });
    }
    if (req.method === 'POST' && req.url === '/api/admin/releases/7/rollout') {
      const { percent } = JSON.parse(body);
      return reply(200, { id: 7, version: '1.1.0', rolloutPercent: percent, status: 'ACTIVE' });
    }
    if (req.method === 'POST' && req.url === '/api/admin/releases/7/pause') {
      const { reason } = JSON.parse(body);
      return reply(200, { id: 7, version: '1.1.0', status: 'PAUSED', pauseReason: reason ?? 'Pausada manualmente' });
    }
    if (req.method === 'GET' && req.url === '/api/admin/releases') {
      return reply(200, [{
        id: 7, version: '1.1.0', status: 'PAUSED', rolloutPercent: 20, pauseReason: 'muitos rollbacks',
        counts: { OFFERED: 1, DOWNLOADING: 0, INSTALLED: 0, CONFIRMED: 2, ROLLED_BACK: 3, FAILED: 0 },
      }]);
    }
    if (req.method === 'GET' && req.url === '/api/admin/devices') {
      return reply(200, [{ id: 'esp32-01', currentVersion: '1.0.0', battery: null, lastSeenAt: '2026-11-02T10:00:00Z' }]);
    }
    return reply(404, { status: 404, detail: 'Versão 99 não encontrada' }, 'application/problem+json');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

function capture() {
  const lines = [];
  return { lines, io: { log: (l) => lines.push(l), error: (l) => lines.push(l) } };
}

const env = () => ({ OTA_FLEET_SERVER: baseUrl, OTA_FLEET_ADMIN_TOKEN: TOKEN });

test('publish lê a versão da imagem, assina e envia multipart', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'ota-fleet-pub-'));
  await run(['keygen', '--out', dir], env(), capture().io);
  const image = buildFakeImage({ version: '1.1.0', size: 4096 });
  const file = join(dir, 'fw.bin');
  await writeFile(file, image);

  received.length = 0;
  const { lines, io } = capture();
  await run(['publish', file, '--key', join(dir, 'private.pem'), '--rollout', '5'], env(), io);

  const request = received[0];
  assert.equal(request.method, 'POST');
  assert.match(request.headers['content-type'], /multipart\/form-data/);
  const text = request.body.toString('latin1');
  assert.match(text, /name="rolloutPercent"\r\n\r\n5/);
  assert.match(text, new RegExp(`name="sha256"\\r\\n\\r\\n${sha256Hex(image)}`));
  assert.ok(request.body.includes(image), 'o binário vai inteiro no campo file');

  const signature = /name="signature"\r\n\r\n([A-Za-z0-9+/=]+)/.exec(text)[1];
  assert.ok(verifyFirmware(image, signature, await readFile(join(dir, 'public.pem'), 'utf8')));
  assert.match(lines[0], /Publicada versão 1\.1\.0 \(id 7\)/);
});

test('publish recusa imagem com versão fora do semver antes de enviar', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'ota-fleet-pub-'));
  const file = join(dir, 'fw.bin');
  await writeFile(file, buildFakeImage({ version: 'dev-build', size: 1024 }));
  received.length = 0;
  await assert.rejects(run(['publish', file, '--key', 'x.pem'], env(), capture().io), /MAJOR\.MINOR\.PATCH/);
  assert.equal(received.length, 0);
});

test('inspect mostra versão, projeto, tamanho e hash', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'ota-fleet-insp-'));
  const image = buildFakeImage({ version: '3.0.1', projectName: 'estufa', size: 2048 });
  const file = join(dir, 'fw.bin');
  await writeFile(file, image);
  const { lines, io } = capture();
  await run(['inspect', file], {}, io);
  assert.deepEqual(lines, [
    'Versão:   3.0.1',
    'Projeto:  estufa',
    'Tamanho:  2048 bytes',
    `SHA-256:  ${sha256Hex(image)}`,
  ]);
});

test('rollout, pause, status e devices', async () => {
  let out = capture();
  await run(['rollout', '7', '50'], env(), out.io);
  assert.match(out.lines[0], /rollout 50%/);

  out = capture();
  await run(['pause', '7', '--reason', 'bug no sensor'], env(), out.io);
  assert.match(out.lines[0], /pausada: bug no sensor/);

  out = capture();
  await run(['status'], env(), out.io);
  assert.match(out.lines[0], /^ID\s+VERSÃO\s+STATUS\s+ROLLOUT/);
  assert.match(out.lines[0], /\n7\s+1\.1\.0\s+PAUSED\s+20%\s+1\s+0\s+0\s+2\s+3\s+0$/);
  assert.match(out.lines[1], /muitos rollbacks/);

  out = capture();
  await run(['devices'], env(), out.io);
  assert.match(out.lines[0], /esp32-01\s+1\.0\.0\s+-/);
});

test('rollout valida a porcentagem', async () => {
  await assert.rejects(run(['rollout', '7', '150'], env(), capture().io), /porcentagem inválida/);
  await assert.rejects(run(['rollout', '7', 'dez'], env(), capture().io), /porcentagem inválida/);
});

test('erros do servidor mostram o detail do ProblemDetail', async () => {
  await assert.rejects(run(['status', '99'], env(), capture().io), /HTTP 404: Versão 99 não encontrada/);
  await assert.rejects(
    run(['devices'], { OTA_FLEET_SERVER: baseUrl, OTA_FLEET_ADMIN_TOKEN: 'errado' }, capture().io),
    /HTTP 401: Token de admin/,
  );
});

test('comando desconhecido devolve código 1', async () => {
  const { lines, io } = capture();
  assert.equal(await run(['explodir'], {}, io), 1);
  assert.match(lines[0], /Comando desconhecido/);
});
