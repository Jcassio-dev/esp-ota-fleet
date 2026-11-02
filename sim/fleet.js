#!/usr/bin/env node
// Simula N aparelhos rodando o protocolo em rodadas, até ninguém mais receber oferta.
// Uso: node sim/fleet.js --devices 50 --rollback-rate 0.4 [--fail-rate 0.05] [--public-key keys/public.pem]

import { readFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';
import { createClient } from '../cli/src/api.js';
import { SimDevice } from './device.js';

export async function runFleet({
  server, token, devices = 50, rollbackRate = 0, failRate = 0, publicKey, version = '1.0.0',
  maxRounds = 10, concurrency = 10, prefix = 'sim', log = console.log, fleet,
}) {
  fleet ??= Array.from({ length: devices }, (_, i) => new SimDevice({
    id: `${prefix}-${String(i + 1).padStart(4, '0')}`, server, version, publicKey, rollbackRate, failRate,
  }));
  const admin = token ? createClient({ server, token }) : null;
  const totals = { confirmed: 0, rolled_back: 0, failed: 0 };
  const announced = new Set(admin
    ? (await admin.listReleases()).filter((r) => r.status === 'PAUSED').map((r) => r.id)
    : []);

  for (let round = 1; round <= maxRounds; round++) {
    const results = await mapLimit(fleet, concurrency, (d) => d.cycle().catch((err) => ({ outcome: 'error', detail: err.message })));
    const count = (o) => results.filter((r) => r.outcome === o).length;
    const round_ = { confirmed: count('confirmed'), rolled_back: count('rolled_back'), failed: count('failed'), errors: count('error') };
    for (const k of Object.keys(totals)) totals[k] += round_[k];

    const active = round_.confirmed + round_.rolled_back + round_.failed + round_.errors;
    log(`Rodada ${round}: ${bar(round_.confirmed, '█')}${bar(round_.rolled_back, '▒')}${bar(round_.failed, '░')} `
      + `${round_.confirmed} confirmados, ${round_.rolled_back} revertidos, ${round_.failed} falhas`
      + (round_.errors ? `, ${round_.errors} erros (${results.find((r) => r.outcome === 'error').detail})` : ''));

    if (admin) {
      for (const r of await admin.listReleases()) {
        if (r.status === 'PAUSED' && r.pauseReason?.startsWith('Pausada automaticamente') && !announced.has(r.id)) {
          announced.add(r.id);
          log(`  ⏸ ${r.version}: ${r.pauseReason}`);
        }
      }
    }
    if (active === 0) break;
  }

  const versions = {};
  for (const d of fleet) versions[d.version] = (versions[d.version] ?? 0) + 1;
  log(`\nVersões na frota: ${Object.entries(versions).map(([v, n]) => `${v} × ${n}`).join(', ')}`);
  return { fleet, totals, versions };
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

function bar(n, char) {
  return char.repeat(Math.min(n, 60));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({
    options: {
      devices: { type: 'string', default: '50' },
      'rollback-rate': { type: 'string', default: '0' },
      'fail-rate': { type: 'string', default: '0' },
      'public-key': { type: 'string' },
      version: { type: 'string', default: '1.0.0' },
      rounds: { type: 'string', default: '10' },
      prefix: { type: 'string', default: 'sim' },
      server: { type: 'string' },
      token: { type: 'string' },
    },
  });
  await runFleet({
    server: values.server ?? process.env.OTA_FLEET_SERVER ?? 'http://localhost:8080',
    token: values.token ?? process.env.OTA_FLEET_ADMIN_TOKEN,
    devices: Number(values.devices),
    rollbackRate: Number(values['rollback-rate']),
    failRate: Number(values['fail-rate']),
    publicKey: values['public-key'] ? await readFile(values['public-key'], 'utf8') : undefined,
    version: values.version,
    maxRounds: Number(values.rounds),
    prefix: values.prefix,
  }).catch((err) => {
    console.error(`Erro: ${err.message}`);
    process.exitCode = 1;
  });
}
