'use strict';

// Plays decoded audio through a small jitter buffer. Network delivery is bursty, so playback
// starts only once TARGET_SECONDS are queued, and excess backlog is discarded to stay live.
const TARGET_SECONDS = 0.08;
const MAX_SECONDS = 0.3;
const CAPACITY_SECONDS = 2;

class PlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(Math.ceil(sampleRate * CAPACITY_SECONDS));
    this.readIndex = 0;
    this.available = 0;
    this.primed = false;
    this.port.onmessage = (event) => {
      if (event.data.reset) {
        this.available = 0;
        this.primed = false;
      } else {
        this.enqueue(event.data.samples, event.data.rate);
      }
    };
  }

  enqueue(samples, rate) {
    const input = rate === sampleRate ? samples : this.resample(samples, rate);
    const capacity = this.buffer.length;
    let writeIndex = (this.readIndex + this.available) % capacity;
    for (let i = 0; i < input.length; i++) {
      this.buffer[writeIndex] = input[i];
      writeIndex = (writeIndex + 1) % capacity;
    }
    this.available += input.length;

    const limit = Math.min(capacity, Math.floor(sampleRate * MAX_SECONDS));
    if (this.available > limit) {
      const keep = Math.floor(sampleRate * TARGET_SECONDS);
      this.readIndex = (this.readIndex + this.available - keep) % capacity;
      this.available = keep;
    }
  }

  resample(samples, rate) {
    const length = Math.floor(samples.length * sampleRate / rate);
    const output = new Float32Array(length);
    const step = rate / sampleRate;
    for (let i = 0; i < length; i++) {
      const position = i * step;
      const index = Math.floor(position);
      const next = Math.min(index + 1, samples.length - 1);
      const fraction = position - index;
      output[i] = samples[index] * (1 - fraction) + samples[next] * fraction;
    }
    return output;
  }

  process(_inputs, outputs) {
    const output = outputs[0][0];
    if (!output) return true;
    if (!this.primed && this.available >= sampleRate * TARGET_SECONDS) this.primed = true;
    if (!this.primed) return true;
    if (this.available < output.length) {
      // Underrun: fall silent and rebuild the buffer rather than stutter on every packet.
      this.primed = false;
      return true;
    }
    const capacity = this.buffer.length;
    for (let i = 0; i < output.length; i++) {
      output[i] = this.buffer[this.readIndex];
      this.readIndex = (this.readIndex + 1) % capacity;
    }
    this.available -= output.length;
    return true;
  }
}

registerProcessor('player', PlayerProcessor);
