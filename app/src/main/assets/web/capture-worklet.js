'use strict';

// Converts microphone audio to 16-bit PCM and hands it to the page in 40 ms chunks.
const CHUNK_SECONDS = 0.04;

class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.chunk = new Int16Array(Math.round(sampleRate * CHUNK_SECONDS));
    this.filled = 0;
    this.port.onmessage = (event) => {
      this.active = event.data.active;
      this.filled = 0;
    };
  }

  process(inputs) {
    const input = inputs[0][0];
    if (!this.active || !input) return true;
    for (let i = 0; i < input.length; i++) {
      const sample = Math.max(-1, Math.min(1, input[i]));
      this.chunk[this.filled++] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
      if (this.filled === this.chunk.length) {
        const full = this.chunk;
        this.port.postMessage(full.buffer, [full.buffer]);
        this.chunk = new Int16Array(full.length);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('capture', CaptureProcessor);
