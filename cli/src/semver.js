// Mesma regra de versão do servidor e do firmware: MAJOR.MINOR.PATCH, sem sufixos.

const FORMAT = /^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/;

export function parseSemVer(text) {
  const match = FORMAT.exec(text ?? '');
  if (!match) return null;
  return match.slice(1, 4).map(Number);
}

export function compareSemVer(a, b) {
  const [x, y] = [parseSemVer(a), parseSemVer(b)];
  if (!x || !y) throw new Error(`versão inválida: ${!x ? a : b}`);
  for (let i = 0; i < 3; i++) {
    if (x[i] !== y[i]) return x[i] - y[i];
  }
  return 0;
}
