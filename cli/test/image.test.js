import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildFakeImage, InvalidImageError, readDescriptor } from '../src/image.js';
import { compareSemVer, parseSemVer } from '../src/semver.js';

test('lê versão e projeto de uma imagem montada', () => {
  const image = buildFakeImage({ version: '2.3.4', projectName: 'sensor', size: 1024 });
  assert.deepEqual(readDescriptor(image), { version: '2.3.4', projectName: 'sensor' });
  assert.equal(image.length, 1024);
});

test('recusa imagens inválidas', () => {
  assert.throws(() => readDescriptor(Buffer.alloc(50)), InvalidImageError);

  const wrongMagic = buildFakeImage({ version: '1.0.0', size: 512 });
  wrongMagic[0] = 0x00;
  assert.throws(() => readDescriptor(wrongMagic), /0xE9/);

  const noDesc = buildFakeImage({ version: '1.0.0', size: 512 });
  noDesc.writeUInt32LE(0, 32);
  assert.throws(() => readDescriptor(noDesc), /magic_word/);

  const noTerminator = buildFakeImage({ version: '1.0.0', size: 512 });
  noTerminator.fill(0x41, 48, 80);
  assert.throws(() => readDescriptor(noTerminator), /version/);
});

test('não deixa montar versão que não cabe no campo', () => {
  assert.throws(() => buildFakeImage({ version: '1'.repeat(32) }), RangeError);
});

test('semver segue a mesma regra do servidor', () => {
  assert.deepEqual(parseSemVer('1.10.0'), [1, 10, 0]);
  for (const bad of ['1.0', 'v1.0.0', '01.0.0', '1.0.0-rc1', '', undefined]) {
    assert.equal(parseSemVer(bad), null, String(bad));
  }
  assert.ok(compareSemVer('1.10.0', '1.9.9') > 0);
  assert.equal(compareSemVer('1.0.0', '1.0.0'), 0);
  assert.ok(compareSemVer('0.9.0', '1.0.0') < 0);
});
