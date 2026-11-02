// Implementação de cada comando. Recebem as opções já interpretadas e um objeto `out`
// para escrever, o que deixa os testes capturarem a saída sem mexer no console.

import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { createClient } from './api.js';
import { generateKeys, readKey, sha256Hex, signFirmware } from './crypto.js';
import { readDescriptor } from './image.js';
import { parseSemVer } from './semver.js';

const STATES = ['OFFERED', 'DOWNLOADING', 'INSTALLED', 'CONFIRMED', 'ROLLED_BACK', 'FAILED'];

export async function keygen({ out = 'keys', firmware, force }, io) {
  const paths = await generateKeys({ outDir: out, firmwarePath: firmware, force });
  io.log(`Chave privada: ${paths.privatePath} (permissão 0600, não compartilhe)`);
  io.log(`Chave pública: ${paths.publicPath}`);
  if (paths.firmwarePath) io.log(`Copiada para o firmware: ${paths.firmwarePath}`);
}

export async function inspect({ file }, io) {
  const firmware = await readFile(requireArg(file, 'arquivo .bin'));
  const desc = readDescriptor(firmware);
  io.log(`Versão:   ${desc.version}${parseSemVer(desc.version) ? '' : '  (fora do formato MAJOR.MINOR.PATCH)'}`);
  io.log(`Projeto:  ${desc.projectName}`);
  io.log(`Tamanho:  ${firmware.length} bytes`);
  io.log(`SHA-256:  ${sha256Hex(firmware)}`);
  return { ...desc, size: firmware.length, sha256: sha256Hex(firmware) };
}

export async function publish({ file, key, rollout = '0', server, token }, io) {
  const firmware = await readFile(requireArg(file, 'arquivo .bin'));
  const desc = readDescriptor(firmware);
  if (!parseSemVer(desc.version)) {
    throw new Error(`a versão dentro da imagem (${desc.version}) não segue MAJOR.MINOR.PATCH`);
  }
  const rolloutPercent = parsePercent(rollout);
  const sha256 = sha256Hex(firmware);
  const signature = signFirmware(firmware, await readKey(requireArg(key, '--key')));

  const release = await createClient({ server, token }).publish({
    firmware, filename: basename(file), signature, sha256, rolloutPercent,
  });
  io.log(`Publicada versão ${release.version} (id ${release.id}), rollout ${release.rolloutPercent}%`);
  io.log(`SHA-256: ${sha256}`);
  return release;
}

export async function rollout({ id, percent, server, token }, io) {
  const release = await createClient({ server, token }).rollout(requireArg(id, 'id'), parsePercent(percent));
  io.log(`Versão ${release.version} (id ${release.id}): rollout ${release.rolloutPercent}%, ${release.status}`);
  return release;
}

export async function pause({ id, reason, server, token }, io) {
  const release = await createClient({ server, token }).pause(requireArg(id, 'id'), reason);
  io.log(`Versão ${release.version} (id ${release.id}) pausada: ${release.pauseReason}`);
  return release;
}

export async function status({ id, server, token }, io) {
  const client = createClient({ server, token });
  const releases = id ? [await client.getRelease(id)] : await client.listReleases();
  if (releases.length === 0) {
    io.log('Nenhuma versão publicada.');
    return releases;
  }
  io.log(formatTable(
    ['ID', 'VERSÃO', 'STATUS', 'ROLLOUT', ...STATES.map(shortState)],
    releases.map((r) => [r.id, r.version, r.status, `${r.rolloutPercent}%`, ...STATES.map((s) => r.counts?.[s] ?? 0)]),
  ));
  for (const r of releases.filter((r) => r.pauseReason)) {
    io.log(`\n${r.version}: ${r.pauseReason}`);
  }
  return releases;
}

export async function devices({ server, token }, io) {
  const list = await createClient({ server, token }).listDevices();
  if (list.length === 0) {
    io.log('Nenhum aparelho fez check-in ainda.');
    return list;
  }
  io.log(formatTable(
    ['APARELHO', 'VERSÃO', 'BATERIA', 'ÚLTIMO CHECK-IN'],
    list.map((d) => [d.id, d.currentVersion, d.battery == null ? '-' : `${d.battery}%`, d.lastSeenAt]),
  ));
  return list;
}

function shortState(state) {
  return { OFFERED: 'OFERT', DOWNLOADING: 'BAIX', INSTALLED: 'INST', CONFIRMED: 'OK', ROLLED_BACK: 'REVERT', FAILED: 'FALHA' }[state];
}

export function formatTable(headers, rows) {
  const cells = [headers, ...rows].map((row) => row.map(String));
  const widths = headers.map((_, i) => Math.max(...cells.map((row) => row[i].length)));
  return cells.map((row) => row.map((cell, i) => cell.padEnd(widths[i])).join('  ').trimEnd()).join('\n');
}

function parsePercent(value) {
  const n = Number(value);
  if (!Number.isInteger(n) || n < 0 || n > 100) {
    throw new Error(`porcentagem inválida: ${value} (use um inteiro de 0 a 100)`);
  }
  return n;
}

function requireArg(value, name) {
  if (value === undefined || value === '') throw new Error(`faltou ${name}`);
  return value;
}
