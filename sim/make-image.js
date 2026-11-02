#!/usr/bin/env node
// Gera uma imagem falsa válida (cabeçalho 0xE9 + esp_app_desc_t + conteúdo aleatório).
// Uso: node sim/make-image.js --version 1.1.0 --out fake.bin [--size 262144] [--project nome]

import { writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { buildFakeImage } from '../cli/src/image.js';
import { parseSemVer } from '../cli/src/semver.js';

const { values } = parseArgs({
  options: {
    version: { type: 'string' },
    out: { type: 'string' },
    size: { type: 'string', default: String(256 * 1024) },
    project: { type: 'string', default: 'ota-fleet-device' },
  },
});

if (!values.version || !values.out) {
  console.error('Uso: node sim/make-image.js --version 1.1.0 --out fake.bin [--size bytes] [--project nome]');
  process.exit(1);
}
if (!parseSemVer(values.version)) {
  console.error(`Aviso: ${values.version} não segue MAJOR.MINOR.PATCH; o servidor vai recusar.`);
}

const image = buildFakeImage({ version: values.version, projectName: values.project, size: Number(values.size) });
await writeFile(values.out, image);
console.log(`Imagem ${values.version} gravada em ${values.out} (${image.length} bytes)`);
