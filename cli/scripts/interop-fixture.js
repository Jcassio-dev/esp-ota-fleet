#!/usr/bin/env node
// Gera a fixture do teste de interoperabilidade: uma imagem assinada pela CLI que o
// servidor Java precisa aceitar. A chave privada é temporária e descartada no fim.

import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateKeys, sha256Hex, signFirmware } from '../src/crypto.js';
import { buildFakeImage } from '../src/image.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const target = join(root, 'server', 'src', 'test', 'resources', 'interop');

const temp = await mkdtemp(join(tmpdir(), 'ota-fleet-interop-'));
try {
  const { privatePath, publicPath } = await generateKeys({ outDir: temp });
  const image = buildFakeImage({ version: '1.2.3', projectName: 'interop', size: 20_000 });
  const signature = signFirmware(image, await readFile(privatePath, 'utf8'));

  await mkdir(target, { recursive: true });
  await writeFile(join(target, 'firmware.bin'), image);
  await writeFile(join(target, 'firmware.sig'), signature + '\n');
  await writeFile(join(target, 'firmware.sha256'), sha256Hex(image) + '\n');
  await writeFile(join(target, 'public.pem'), await readFile(publicPath));
  console.log(`Fixture gravada em ${target}`);
} finally {
  await rm(temp, { recursive: true, force: true });
}
