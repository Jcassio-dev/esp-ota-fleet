// Chaves e assinatura: ECDSA P-256 sobre o SHA-256 do arquivo inteiro, assinatura em DER.

import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';
import { chmod, copyFile, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

export function sha256Hex(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

/** Devolve a assinatura DER em base64, o formato que o servidor e o firmware esperam. */
export function signFirmware(buffer, privateKeyPem) {
  const key = createPrivateKey(privateKeyPem);
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('a chave privada precisa ser EC P-256');
  }
  return sign('sha256', buffer, key).toString('base64');
}

export function verifyFirmware(buffer, signatureBase64, publicKeyPem) {
  return verify('sha256', buffer, createPublicKey(publicKeyPem), Buffer.from(signatureBase64, 'base64'));
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

/**
 * Gera o par de chaves. A privada (PKCS#8, 0600) fica só na máquina de quem publica;
 * a pública (SPKI) pode ir para o firmware e para o servidor.
 */
export async function generateKeys({ outDir, firmwarePath, force = false }) {
  const privatePath = join(outDir, 'private.pem');
  const publicPath = join(outDir, 'public.pem');
  const targets = [privatePath, publicPath, firmwarePath].filter(Boolean);

  if (!force) {
    for (const path of targets) {
      if (await exists(path)) {
        throw new Error(`${path} já existe; use --force para sobrescrever`);
      }
    }
  }

  const { privateKey, publicKey } = generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
    privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });

  await mkdir(outDir, { recursive: true });
  await writeFile(privatePath, privateKey, { mode: 0o600 });
  await chmod(privatePath, 0o600);
  await writeFile(publicPath, publicKey);
  if (firmwarePath) {
    await mkdir(dirname(firmwarePath), { recursive: true });
    await copyFile(publicPath, firmwarePath);
  }
  return { privatePath, publicPath, firmwarePath };
}

export async function readKey(path) {
  return readFile(path, 'utf8');
}
