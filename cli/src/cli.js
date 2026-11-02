#!/usr/bin/env node
// Ponto de entrada da CLI: interpreta os argumentos e chama o comando.

import { parseArgs } from 'node:util';
import * as commands from './commands.js';

const USAGE = `Uso: ota-fleet <comando> [opções]

Comandos:
  keygen [--out keys/] [--firmware caminho.pem] [--force]
  inspect <arquivo.bin>
  publish <arquivo.bin> --key keys/private.pem [--rollout 0]
  rollout <id> <porcentagem>
  pause <id> [--reason "motivo"]
  status [id]
  devices

Servidor e token: --server/--token ou OTA_FLEET_SERVER e OTA_FLEET_ADMIN_TOKEN.`;

const OPTIONS = {
  out: { type: 'string' },
  firmware: { type: 'string' },
  force: { type: 'boolean' },
  key: { type: 'string' },
  rollout: { type: 'string' },
  reason: { type: 'string' },
  server: { type: 'string' },
  token: { type: 'string' },
  help: { type: 'boolean', short: 'h' },
};

export async function run(argv, env = process.env, io = console) {
  const { values, positionals } = parseArgs({ args: argv, options: OPTIONS, allowPositionals: true });
  const [command, ...rest] = positionals;
  if (!command || values.help) {
    io.log(USAGE);
    return command ? 0 : 1;
  }

  const options = {
    ...values,
    server: values.server ?? env.OTA_FLEET_SERVER ?? 'http://localhost:8080',
    token: values.token ?? env.OTA_FLEET_ADMIN_TOKEN,
  };

  switch (command) {
    case 'keygen': await commands.keygen(options, io); break;
    case 'inspect': await commands.inspect({ ...options, file: rest[0] }, io); break;
    case 'publish': await commands.publish({ ...options, file: rest[0] }, io); break;
    case 'rollout': await commands.rollout({ ...options, id: rest[0], percent: rest[1] }, io); break;
    case 'pause': await commands.pause({ ...options, id: rest[0] }, io); break;
    case 'status': await commands.status({ ...options, id: rest[0] }, io); break;
    case 'devices': await commands.devices(options, io); break;
    default:
      io.error(`Comando desconhecido: ${command}\n\n${USAGE}`);
      return 1;
  }
  return 0;
}

const isMain = import.meta.url === new URL(process.argv[1], 'file://').href
  || process.argv[1]?.endsWith('cli.js');
if (isMain) {
  run(process.argv.slice(2)).then(
    (code) => { process.exitCode = code; },
    (err) => {
      console.error(`Erro: ${err.message}`);
      process.exitCode = 1;
    },
  );
}
