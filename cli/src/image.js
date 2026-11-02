// Leitura do esp_app_desc_t de dentro de uma imagem de aplicação do ESP32.
//
// offset 0   esp_image_header_t (24 bytes), byte 0 = 0xE9
// offset 24  esp_image_segment_header_t (8 bytes)
// offset 32  magic_word = 0xABCD5432 (little-endian)
// offset 48  version[32], terminada em \0
// offset 80  project_name[32], terminada em \0

import { randomBytes } from 'node:crypto';

export const IMAGE_MAGIC = 0xe9;
export const APP_DESC_MAGIC = 0xabcd5432;
export const MIN_LENGTH = 112;

const MAGIC_OFFSET = 32;
const VERSION_OFFSET = 48;
const PROJECT_OFFSET = 80;
const FIELD_LENGTH = 32;

export class InvalidImageError extends Error {}

export function readDescriptor(buffer) {
  if (!buffer || buffer.length < MIN_LENGTH) {
    throw new InvalidImageError('arquivo pequeno demais para ser uma imagem do ESP32');
  }
  if (buffer[0] !== IMAGE_MAGIC) {
    throw new InvalidImageError('cabeçalho da imagem não começa com 0xE9');
  }
  if (buffer.readUInt32LE(MAGIC_OFFSET) !== APP_DESC_MAGIC) {
    throw new InvalidImageError('esp_app_desc_t não encontrado (magic_word inválido)');
  }
  const version = readCString(buffer, VERSION_OFFSET, 'version');
  const projectName = readCString(buffer, PROJECT_OFFSET, 'project_name');
  if (version === '') {
    throw new InvalidImageError('versão vazia dentro da imagem');
  }
  return { version, projectName };
}

function readCString(buffer, offset, field) {
  const end = buffer.indexOf(0, offset);
  if (end === -1 || end >= offset + FIELD_LENGTH) {
    throw new InvalidImageError(`campo ${field} sem terminador \\0`);
  }
  return buffer.toString('utf8', offset, end);
}

/** Monta uma imagem falsa com o layout real, para testar sem placa. */
export function buildFakeImage({ version, projectName = 'ota-fleet-device', size = 64 * 1024 }) {
  if (size < MIN_LENGTH) {
    throw new RangeError(`size deve ser pelo menos ${MIN_LENGTH}`);
  }
  const buffer = randomBytes(size);
  buffer[0] = IMAGE_MAGIC;
  buffer.writeUInt32LE(APP_DESC_MAGIC, MAGIC_OFFSET);
  writeCString(buffer, VERSION_OFFSET, version);
  writeCString(buffer, PROJECT_OFFSET, projectName);
  return buffer;
}

function writeCString(buffer, offset, value) {
  const bytes = Buffer.from(value, 'utf8');
  if (bytes.length >= FIELD_LENGTH) {
    throw new RangeError(`"${value}" não cabe em ${FIELD_LENGTH - 1} bytes`);
  }
  buffer.fill(0, offset, offset + FIELD_LENGTH);
  bytes.copy(buffer, offset);
}
