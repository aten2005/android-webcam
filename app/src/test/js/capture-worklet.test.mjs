import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../main/assets/web/capture-worklet.js', import.meta.url), 'utf8');
const RENDER_QUANTUM = 128;

function createProcessor(sampleRate) {
  const posted = [];
  const port = {
    onmessage: null,
    // structuredClone really detaches the transferred buffer, as a MessagePort does.
    postMessage: (buffer, transfer) => posted.push(structuredClone(buffer, { transfer })),
  };
  let Registered = null;
  vm.runInNewContext(source, {
    sampleRate,
    AudioWorkletProcessor: class { constructor() { this.port = port; } },
    registerProcessor: (_name, processorClass) => { Registered = processorClass; },
  });
  const processor = new Registered();
  return {
    posted,
    setActive: (active) => port.onmessage({ data: { active } }),
    render: (seconds) => {
      const quanta = Math.round(sampleRate * seconds / RENDER_QUANTUM);
      for (let i = 0; i < quanta; i++) processor.process([[new Float32Array(RENDER_QUANTUM).fill(0.5)]]);
    },
  };
}

for (const sampleRate of [16000, 48000]) {
  test(`keeps posting 40 ms chunks at ${sampleRate} Hz`, () => {
    const capture = createProcessor(sampleRate);
    capture.setActive(true);
    capture.render(0.4);
    assert.equal(capture.posted.length, 10);
    for (const chunk of capture.posted) assert.equal(chunk.byteLength, sampleRate * 0.04 * 2);
    assert.equal(new Int16Array(capture.posted[9])[0], Math.trunc(0.5 * 0x7fff));
  });
}

test('posts nothing while inactive', () => {
  const capture = createProcessor(16000);
  capture.render(0.4);
  assert.equal(capture.posted.length, 0);
});

test('activating discards a partially filled chunk', () => {
  const capture = createProcessor(16000);
  capture.setActive(true);
  capture.render(0.032);
  capture.setActive(true);
  capture.render(0.032);
  assert.equal(capture.posted.length, 0);
  capture.render(0.008);
  assert.equal(capture.posted.length, 1);
});
