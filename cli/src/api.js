// Cliente HTTP das rotas de admin. Erros do servidor chegam como ProblemDetail.

export class ApiError extends Error {
  constructor(status, detail) {
    super(`HTTP ${status}: ${detail}`);
    this.status = status;
    this.detail = detail;
  }
}

export function createClient({ server, token }) {
  if (!server) throw new Error('servidor não informado (--server ou OTA_FLEET_SERVER)');
  const base = server.replace(/\/+$/, '');

  async function request(method, path, { json, form } = {}) {
    const headers = {};
    if (token) headers['X-Admin-Token'] = token;
    let body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (form) {
      body = form;
    }

    let response;
    try {
      response = await fetch(base + path, { method, headers, body });
    } catch (err) {
      throw new Error(`não foi possível conectar em ${base}: ${err.cause?.code ?? err.message}`);
    }

    const text = await response.text();
    const data = text ? safeJson(text) : null;
    if (!response.ok) {
      const detail = data?.detail ?? data?.title ?? (text || response.statusText);
      throw new ApiError(response.status, detail);
    }
    return data;
  }

  return {
    publish({ firmware, filename, signature, sha256, rolloutPercent }) {
      const form = new FormData();
      form.append('file', new Blob([firmware], { type: 'application/octet-stream' }), filename);
      form.append('signature', signature);
      form.append('sha256', sha256);
      form.append('rolloutPercent', String(rolloutPercent));
      return request('POST', '/api/admin/releases', { form });
    },
    listReleases: () => request('GET', '/api/admin/releases'),
    getRelease: (id) => request('GET', `/api/admin/releases/${encodeURIComponent(id)}`),
    rollout: (id, percent) => request('POST', `/api/admin/releases/${encodeURIComponent(id)}/rollout`, { json: { percent } }),
    pause: (id, reason) => request('POST', `/api/admin/releases/${encodeURIComponent(id)}/pause`, { json: { reason } }),
    listDevices: () => request('GET', '/api/admin/devices'),
  };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
