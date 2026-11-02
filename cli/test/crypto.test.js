import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtemp, readFile, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { generateKeys, sha256Hex, signFirmware, verifyFirmware } from '../src/crypto.js';
import { buildFakeImage } from '../src/image.js';

async function tempDir() {
  return mkdtemp(join(tmpdir(), 'ota-fleet-cli-'));
}

test('keygen grava privada com 0600 e copia a pública para o firmware', async () => {
  const dir = await tempDir();
  const firmwarePath = join(dir, 'firmware', 'main', 'signing_key.pem');
  const paths = await generateKeys({ outDir: join(dir, 'keys'), firmwarePath });

  const mode = (await stat(paths.privatePath)).mode & 0o777;
  assert.equal(mode, 0o600);
  const publicPem = await readFile(paths.publicPath, 'utf8');
  assert.match(publicPem, /BEGIN PUBLIC KEY/);
  assert.equal(await readFile(firmwarePath, 'utf8'), publicPem);
  assert.match(await readFile(paths.privatePath, 'utf8'), /BEGIN PRIVATE KEY/);
});

test('keygen não sobrescreve sem --force', async () => {
  const dir = await tempDir();
  await generateKeys({ outDir: dir });
  const before = await readFile(join(dir, 'private.pem'), 'utf8');

  await assert.rejects(generateKeys({ outDir: dir }), /já existe/);
  assert.equal(await readFile(join(dir, 'private.pem'), 'utf8'), before);

  await generateKeys({ outDir: dir, force: true });
  assert.notEqual(await readFile(join(dir, 'private.pem'), 'utf8'), before);
});

test('keygen não sobrescreve a chave do firmware sem --force', async () => {
  const dir = await tempDir();
  const firmwarePath = join(dir, 'signing_key.pem');
  await writeFile(firmwarePath, 'chave antiga');
  await assert.rejects(generateKeys({ outDir: join(dir, 'keys'), firmwarePath }), /já existe/);
});

test('assina e verifica; adulteração é detectada', async () => {
  const dir = await tempDir();
  const { privatePath, publicPath } = await generateKeys({ outDir: dir });
  const [privatePem, publicPem] = await Promise.all([readFile(privatePath, 'utf8'), readFile(publicPath, 'utf8')]);

  const image = buildFakeImage({ version: '1.1.0', size: 8192 });
  const signature = signFirmware(image, privatePem);
  assert.ok(verifyFirmware(image, signature, publicPem));

  const tampered = Buffer.from(image);
  tampered[tampered.length - 1] ^= 1;
  assert.equal(verifyFirmware(tampered, signature, publicPem), false);

  // DER de ECDSA começa com SEQUENCE (0x30)
  assert.equal(Buffer.from(signature, 'base64')[0], 0x30);
});

test('recusa chave que não é P-256', () => {
  const { privateKey } = generateKeyPairSync('ec', {
    namedCurve: 'secp384r1',
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });
  assert.throws(() => signFirmware(Buffer.from('x'), privateKey), /P-256/);
});

test('sha256Hex', () => {
  assert.equal(sha256Hex(Buffer.from('abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});
