#!/usr/bin/env node
// Demonstração completa, base do GIF do README:
//   1. publica uma versão boa e a frota inteira atualiza;
//   2. publica uma versão ruim (40% de rollback) e o servidor pausa sozinho.
//
// Pré-requisitos: servidor rodando com OTA_FLEET_SIGNING_PUBLIC_KEY=keys/public.pem
// e a chave privada em keys/private.pem (node cli/src/cli.js keygen).
// Uso: node sim/demo.js [--devices 40] [--key keys/private.pem]

import { readFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { createClient } from '../cli/src/api.js';
import { sha256Hex, signFirmware } from '../cli/src/crypto.js';
import { buildFakeImage } from '../cli/src/image.js';
import { compareSemVer } from '../cli/src/semver.js';
import { runFleet } from './fleet.js';

const { values } = parseArgs({
  options: {
    devices: { type: 'string', default: '40' },
    key: { type: 'string', default: 'keys/private.pem' },
    'public-key': { type: 'string', default: 'keys/public.pem' },
    server: { type: 'string' },
    token: { type: 'string' },
  },
});

const server = values.server ?? process.env.OTA_FLEET_SERVER ?? 'http://localhost:8080';
const token = values.token ?? process.env.OTA_FLEET_ADMIN_TOKEN;
if (!token) {
  console.error('Defina OTA_FLEET_ADMIN_TOKEN (o mesmo do servidor) ou passe --token.');
  process.exit(1);
}
const admin = createClient({ server, token });
const privateKey = await readFile(values.key, 'utf8');
const publicKey = await readFile(values['public-key'], 'utf8');

// Escolhe versões acima de tudo que já foi publicado, para a demo rodar mais de uma vez.
const published = (await admin.listReleases()).map((r) => r.version);
const top = published.reduce((max, v) => (compareSemVer(v, max) > 0 ? v : max), '1.0.0');
const [major, minor] = top.split('.').map(Number);
const base = `${major}.${minor}.0`;
const good = `${major}.${minor + 1}.0`;
const bad = `${major}.${minor + 2}.0`;
const prefix = `demo${Date.now().toString(36)}`;

async function publish(version) {
  const firmware = buildFakeImage({ version, size: 200 * 1024 });
  const release = await admin.publish({
    firmware, filename: `v${version}.bin`, signature: signFirmware(firmware, privateKey),
    sha256: sha256Hex(firmware), rolloutPercent: 100,
  });
  console.log(`\n▶ Publicada ${version} (id ${release.id}) com rollout de 100%`);
  return release;
}

console.log(`Frota de ${values.devices} aparelhos começando em ${base}`);

await publish(good);
const { fleet } = await runFleet({
  server, token, devices: Number(values.devices), version: base, publicKey, rollbackRate: 0.02, prefix,
});

const release = await publish(bad);
await runFleet({ server, token, fleet: fleet.map((d) => Object.assign(d, { rollbackRate: 0.4 })) });

const final = await admin.getRelease(release.id);
console.log(`\nStatus de ${bad}: ${final.status}${final.pauseReason ? ` (${final.pauseReason})` : ''}`);
