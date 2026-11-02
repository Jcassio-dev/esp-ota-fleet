// Um aparelho falso que segue o mesmo protocolo do firmware:
// check-in → oferta → download em blocos com Range → hash, assinatura e versão → eventos.

import { createHash } from 'node:crypto';
import { readDescriptor } from '../cli/src/image.js';
import { verifyFirmware } from '../cli/src/crypto.js';
import { compareSemVer } from '../cli/src/semver.js';

export class SimDevice {
  /**
   * @param {object} opts
   * @param {string} opts.id
   * @param {string} opts.server       URL base do servidor
   * @param {string} [opts.version]    versão inicial
   * @param {string} [opts.publicKey]  PEM da chave pública; sem ela a assinatura não é conferida
   * @param {number} [opts.rollbackRate] chance de a versão nova falhar no autoteste
   * @param {number} [opts.failRate]   chance de o download falhar no meio
   * @param {() => number} [opts.random]
   */
  constructor({ id, server, version = '1.0.0', publicKey, rollbackRate = 0, failRate = 0, random = Math.random }) {
    Object.assign(this, { id, server: server.replace(/\/+$/, ''), version, publicKey, rollbackRate, failRate, random });
    this.battery = 30 + Math.floor(random() * 70);
  }

  /** Um ciclo completo. Devolve o que aconteceu, para o painel do simulador. */
  async cycle() {
    const { update } = await this.#post(`/api/devices/${this.id}/checkin`, { version: this.version, battery: this.battery });
    if (!update) return { outcome: 'idle' };

    await this.#event(update.releaseId, 'DOWNLOADING');
    let image;
    try {
      image = await this.#download(update);
      this.#verify(image, update);
    } catch (err) {
      await this.#event(update.releaseId, 'FAILED', err.message);
      return { outcome: 'failed', version: update.version, detail: err.message };
    }
    await this.#event(update.releaseId, 'INSTALLED');

    // "Reboot" na versão nova: o autoteste passa ou o bootloader volta para a anterior.
    if (this.random() < this.rollbackRate) {
      await this.#event(update.releaseId, 'ROLLED_BACK', 'autoteste não passou (simulado)');
      return { outcome: 'rolled_back', version: update.version };
    }
    const previous = this.version;
    this.version = update.version;
    await this.#event(update.releaseId, 'CONFIRMED', `atualizado de ${previous}`);
    return { outcome: 'confirmed', version: update.version };
  }

  async #download(update) {
    const chunks = [];
    const failAt = this.random() < this.failRate ? Math.floor(update.size / 2) : -1;
    for (let offset = 0; offset < update.size; offset += update.chunkSize) {
      if (failAt >= 0 && offset >= failAt) {
        throw new Error(`conexão caiu no offset ${offset} (simulado)`);
      }
      const end = Math.min(offset + update.chunkSize, update.size) - 1;
      const res = await fetch(this.server + update.url, { headers: { Range: `bytes=${offset}-${end}` } });
      if (res.status !== 206 && !(res.status === 200 && offset === 0)) {
        throw new Error(`download respondeu ${res.status} no offset ${offset}`);
      }
      const body = Buffer.from(await res.arrayBuffer());
      if (res.status === 200) return body;
      if (body.length !== end - offset + 1) {
        throw new Error(`bloco incompleto no offset ${offset}`);
      }
      chunks.push(body);
    }
    return Buffer.concat(chunks);
  }

  #verify(image, update) {
    if (image.length !== update.size) throw new Error('tamanho diferente do oferecido');
    const sha = createHash('sha256').update(image).digest('hex');
    if (sha !== update.sha256) throw new Error('SHA-256 não confere');
    if (this.publicKey && !verifyFirmware(image, update.signature, this.publicKey)) {
      throw new Error('assinatura inválida');
    }
    const { version } = readDescriptor(image);
    if (version !== update.version) throw new Error(`imagem traz ${version}, oferta dizia ${update.version}`);
    if (compareSemVer(version, this.version) <= 0) throw new Error(`downgrade recusado (${this.version} → ${version})`);
  }

  async #event(releaseId, type, detail) {
    await this.#post(`/api/devices/${this.id}/events`, { releaseId, type, detail }, true);
  }

  async #post(path, body, allowConflict = false) {
    const res = await fetch(this.server + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 204) return null;
    if (allowConflict && res.status === 409) return null;
    const text = await res.text();
    if (!res.ok) throw new Error(`${path} respondeu ${res.status}: ${text}`);
    return JSON.parse(text);
  }
}
