'use strict';

// Converts microphone audio to 16-bit PCM and hands it to the page in 40 ms chunks.
const CHUNK_SECONDS = 0.04;

class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.active = false;
    this.chunkLength = Math.round(sampleRate * CHUNK_SECONDS);
    this.chunk = new Int16Array(this.chunkLength);
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
      if (this.filled === this.chunkLength) {
        // Transferring detaches the buffer, after which the old array reports a length of 0.
        this.port.postMessage(this.chunk.buffer, [this.chunk.buffer]);
        this.chunk = new Int16Array(this.chunkLength);
        this.filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('capture', CaptureProcessor);
