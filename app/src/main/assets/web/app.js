'use strict';

const FRAME_VIDEO_KEY = 1;
const FRAME_VIDEO_DELTA = 2;
const FRAME_AUDIO = 3;
const FRAME_TALKBACK_PCM = 16;
const MEDIA_HEADER_BYTES = 9;
const CLOSE_BATTERY_LOW = 4001;
const MAX_DECODE_QUEUE = 5;
const MAX_UPLINK_BUFFER_BYTES = 64 * 1024;
const MIC_IDLE_RELEASE_MS = 60_000;
const ROTATION_KEY = 'webcam.rotationOffset';

const $ = (id) => document.getElementById(id);
const canvas = $('video');
const context2d = canvas.getContext('2d', { alpha: false, desynchronized: true });
const overlay = $('overlay');
const overlayText = $('overlay-text');
const noticeText = $('notice');
const statsText = $('stats');
const buttons = {
  start: $('start'), mute: $('mute'), talk: $('talk'), switchCamera: $('switch-camera'),
  torch: $('torch'), rotate: $('rotate'), fullscreen: $('fullscreen'),
  disconnect: $('disconnect'), logout: $('logout'),
};
const qualitySelect = $('quality');

let socket = null;
let wantConnection = false;
let reconnectDelayMs = 1000;
let reconnectTimer = 0;

let videoDecoder = null;
let videoConfig = null;
let waitingForKey = true;
let pendingFrame = null;
let drawScheduled = false;
let deviceRotation = 0;
let rotationOffset = Number(localStorage.getItem(ROTATION_KEY)) || 0;

let audioContext = null;
let playerNode = null;
let gainNode = null;
let audioDecoder = null;
let muted = false;

let talkWanted = false;
let talking = false;
let micStream = null;
let micContext = null;
let captureNode = null;
let micReleaseTimer = 0;

let pipelineState = null;
let noticeTimer = 0;
let receivedBytes = 0;
let drawnFrames = 0;

// ---------------------------------------------------------------- connection

async function start() {
  if (!('VideoDecoder' in window) || !('AudioDecoder' in window) || !window.isSecureContext) {
    showOverlay('This browser cannot play the stream. Use a current version of Chrome, Edge, Safari or Firefox over HTTPS.', false);
    return;
  }
  buttons.start.disabled = true;
  try {
    await ensureAudioOutput();
  } catch (error) {
    showNotice(`Audio output unavailable: ${error.message}`);
  }
  wantConnection = true;
  connect();
}

function connect() {
  clearTimeout(reconnectTimer);
  showOverlay('Connecting…', false);
  socket = new WebSocket(`wss://${location.host}/ws`);
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => {
    reconnectDelayMs = 1000;
    hideOverlay();
    setControlsEnabled(true);
    if (document.hidden) send({ type: 'pauseVideo', paused: true });
  };
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') handleControl(JSON.parse(event.data));
    else handleMedia(event.data);
  };
  socket.onclose = (event) => {
    socket = null;
    stopTalking();
    setControlsEnabled(false);
    resetDecoders();
    if (event.code === 1008) {
      location.replace('/login');
    } else if (!wantConnection) {
      showOverlay('Disconnected. The camera is off.', true);
    } else if (event.code === CLOSE_BATTERY_LOW) {
      // Reconnecting would only switch the camera back on and drain the battery further.
      wantConnection = false;
      showOverlay('Streaming stopped: the device battery is low.', true);
    } else if (event.code === 1013) {
      wantConnection = false;
      showOverlay('The device is not accepting viewers right now (viewer limit reached or battery low).', true);
    } else {
      showOverlay('Connection lost. Reconnecting…', false);
      reconnectTimer = setTimeout(checkSessionThenConnect, reconnectDelayMs);
      reconnectDelayMs = Math.min(reconnectDelayMs * 2, 15_000);
    }
  };
}

// A failed WebSocket handshake hides its HTTP status, so probe whether the session is still valid.
async function checkSessionThenConnect() {
  try {
    const response = await fetch('/app.js', { method: 'HEAD', cache: 'no-store' });
    if (response.status === 401) {
      location.replace('/login');
      return;
    }
  } catch (_) {
    // Device unreachable; keep retrying.
  }
  if (wantConnection) connect();
}

function disconnect() {
  wantConnection = false;
  clearTimeout(reconnectTimer);
  if (socket) socket.close(1000);
  else showOverlay('Disconnected. The camera is off.', true);
}

function send(message) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

// ---------------------------------------------------------------- incoming messages

function handleControl(message) {
  switch (message.type) {
    case 'videoConfig': configureVideo(message); break;
    case 'audioConfig': configureAudio(message); break;
    case 'state': applyState(message); break;
    case 'notice': showNotice(message.message); break;
    case 'talk': onTalkReply(message); break;
  }
}

function handleMedia(buffer) {
  if (buffer.byteLength <= MEDIA_HEADER_BYTES) return;
  receivedBytes += buffer.byteLength;
  const view = new DataView(buffer);
  const type = view.getUint8(0);
  const timestamp = Number(view.getBigUint64(1));
  const data = new Uint8Array(buffer, MEDIA_HEADER_BYTES);
  if (type === FRAME_AUDIO) decodeAudio(timestamp, data);
  else if (type === FRAME_VIDEO_KEY || type === FRAME_VIDEO_DELTA) decodeVideo(timestamp, data, type === FRAME_VIDEO_KEY);
}

function base64ToBytes(text) {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

// ---------------------------------------------------------------- video

function configureVideo(message) {
  videoConfig = {
    codec: message.codec,
    description: base64ToBytes(message.description),
    codedWidth: message.width,
    codedHeight: message.height,
    optimizeForLatency: true,
  };
  deviceRotation = message.rotation;
  createVideoDecoder();
}

function createVideoDecoder() {
  closeQuietly(videoDecoder);
  waitingForKey = true;
  videoDecoder = new VideoDecoder({
    output: onVideoFrame,
    error: () => {
      // Decoders are unusable after an error; rebuild and restart from a fresh key frame.
      if (videoConfig) createVideoDecoder();
      send({ type: 'needKey' });
    },
  });
  videoDecoder.configure(videoConfig);
}

function decodeVideo(timestamp, data, isKey) {
  if (!videoDecoder || videoDecoder.state !== 'configured' || document.hidden) return;
  if (videoDecoder.decodeQueueSize > MAX_DECODE_QUEUE) {
    // Too slow to keep up: skip ahead to the next key frame instead of falling behind live.
    waitingForKey = true;
  }
  if (waitingForKey) {
    if (!isKey) return;
    waitingForKey = false;
  }
  videoDecoder.decode(new EncodedVideoChunk({ type: isKey ? 'key' : 'delta', timestamp, data }));
}

function onVideoFrame(frame) {
  if (pendingFrame) pendingFrame.close();
  pendingFrame = frame;
  if (!drawScheduled) {
    drawScheduled = true;
    requestAnimationFrame(drawPendingFrame);
  }
}

function drawPendingFrame() {
  drawScheduled = false;
  const frame = pendingFrame;
  pendingFrame = null;
  if (!frame) return;
  const rotation = (deviceRotation + rotationOffset) % 360;
  const sideways = rotation % 180 !== 0;
  const width = sideways ? frame.displayHeight : frame.displayWidth;
  const height = sideways ? frame.displayWidth : frame.displayHeight;
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  context2d.save();
  context2d.translate(width / 2, height / 2);
  context2d.rotate(rotation * Math.PI / 180);
  context2d.drawImage(frame, -frame.displayWidth / 2, -frame.displayHeight / 2);
  context2d.restore();
  // Undrawn frames hold decoder memory; every frame must be closed.
  frame.close();
  drawnFrames++;
}

// ---------------------------------------------------------------- audio playback

async function ensureAudioOutput() {
  if (audioContext) {
    if (audioContext.state === 'suspended') await audioContext.resume();
    return;
  }
  audioContext = new AudioContext({ sampleRate: 48000, latencyHint: 'interactive' });
  await audioContext.audioWorklet.addModule('/player-worklet.js');
  playerNode = new AudioWorkletNode(audioContext, 'player', { numberOfInputs: 0, outputChannelCount: [1] });
  gainNode = audioContext.createGain();
  playerNode.connect(gainNode).connect(audioContext.destination);
  await audioContext.resume();
}

function configureAudio(message) {
  closeQuietly(audioDecoder);
  audioDecoder = new AudioDecoder({
    output: onAudioData,
    error: () => { audioDecoder = null; showNotice('Audio decoding failed in this browser.'); },
  });
  const config = { codec: message.codec, sampleRate: message.sampleRate, numberOfChannels: message.channels };
  if (message.description) config.description = base64ToBytes(message.description);
  audioDecoder.configure(config);
  if (playerNode) playerNode.port.postMessage({ reset: true });
}

function decodeAudio(timestamp, data) {
  // While muted or talking the audio is not heard, so it is not worth decoding either.
  if (!audioDecoder || audioDecoder.state !== 'configured' || !playerNode || muted || talking) return;
  audioDecoder.decode(new EncodedAudioChunk({ type: 'key', timestamp, data }));
}

function onAudioData(audioData) {
  const samples = new Float32Array(audioData.numberOfFrames);
  audioData.copyTo(samples, { planeIndex: 0, format: 'f32-planar' });
  playerNode.port.postMessage({ samples, rate: audioData.sampleRate }, [samples.buffer]);
  audioData.close();
}

function updateOutputGain() {
  if (gainNode) gainNode.gain.value = muted || talking ? 0 : 1;
  if ((muted || talking) && playerNode) playerNode.port.postMessage({ reset: true });
}

// ---------------------------------------------------------------- talkback

async function beginTalking() {
  if (talkWanted || !socket) return;
  talkWanted = true;
  clearTimeout(micReleaseTimer);
  try {
    await ensureMicrophone();
  } catch (error) {
    talkWanted = false;
    showNotice(`Microphone unavailable: ${error.message}`);
    return;
  }
  // The permission prompt can outlast the button press.
  if (talkWanted) send({ type: 'talkStart', sampleRate: micContext.sampleRate });
}

async function ensureMicrophone() {
  if (micStream) {
    if (micContext.state === 'suspended') await micContext.resume();
    return;
  }
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  });
  // Capturing at 16 kHz lets the phone play the samples as-is, with no resampler on either end.
  micContext = new AudioContext({ sampleRate: 16000 });
  await micContext.audioWorklet.addModule('/capture-worklet.js');
  captureNode = new AudioWorkletNode(micContext, 'capture', { numberOfOutputs: 0 });
  captureNode.port.onmessage = (event) => sendTalkback(event.data);
  micContext.createMediaStreamSource(micStream).connect(captureNode);
}

function onTalkReply(message) {
  if (message.granted && talkWanted) {
    talking = true;
    captureNode.port.postMessage({ active: true });
    buttons.talk.classList.add('talking');
    buttons.talk.textContent = 'Talking…';
    updateOutputGain();
  } else {
    if (message.granted) send({ type: 'talkStop' });
    else showNotice(`Cannot talk: ${message.reason || 'refused'}`);
    stopTalking();
  }
}

function sendTalkback(pcmBuffer) {
  if (!talking || !socket || socket.readyState !== WebSocket.OPEN) return;
  if (socket.bufferedAmount > MAX_UPLINK_BUFFER_BYTES) return;
  const message = new Uint8Array(1 + pcmBuffer.byteLength);
  message[0] = FRAME_TALKBACK_PCM;
  message.set(new Uint8Array(pcmBuffer), 1);
  socket.send(message);
}

function stopTalking() {
  const wasActive = talkWanted || talking;
  talkWanted = false;
  if (captureNode) captureNode.port.postMessage({ active: false });
  if (talking) send({ type: 'talkStop' });
  talking = false;
  buttons.talk.classList.remove('talking');
  buttons.talk.textContent = 'Hold to talk';
  updateOutputGain();
  if (wasActive && micStream) {
    clearTimeout(micReleaseTimer);
    micReleaseTimer = setTimeout(releaseMicrophone, MIC_IDLE_RELEASE_MS);
  }
}

// Keeps the microphone warm between presses, then frees it so the browser's recording indicator clears.
function releaseMicrophone() {
  if (talkWanted || !micStream) return;
  micStream.getTracks().forEach((track) => track.stop());
  micContext.close();
  micStream = null;
  micContext = null;
  captureNode = null;
}

// ---------------------------------------------------------------- UI

function applyState(state) {
  pipelineState = state;
  buttons.switchCamera.disabled = state.cameras.length < 2;
  buttons.torch.disabled = !state.torchAvailable;
  buttons.torch.classList.toggle('active', state.torch);
  buttons.mute.disabled = !state.audio;
  qualitySelect.value = state.quality;
}

function setControlsEnabled(connected) {
  buttons.disconnect.disabled = !connected;
  buttons.talk.disabled = !connected;
  qualitySelect.disabled = !connected;
  if (!connected) {
    buttons.mute.disabled = true;
    buttons.switchCamera.disabled = true;
    buttons.torch.disabled = true;
  }
}

function resetDecoders() {
  closeQuietly(videoDecoder);
  closeQuietly(audioDecoder);
  videoDecoder = null;
  audioDecoder = null;
  videoConfig = null;
  if (playerNode) playerNode.port.postMessage({ reset: true });
}

function closeQuietly(decoder) {
  try {
    if (decoder && decoder.state !== 'closed') decoder.close();
  } catch (_) {
    // Already closed.
  }
}

function showOverlay(text, offerConnect) {
  overlayText.textContent = text;
  buttons.start.hidden = !offerConnect;
  buttons.start.disabled = false;
  overlay.hidden = false;
}

function hideOverlay() {
  overlay.hidden = true;
}

function showNotice(text) {
  noticeText.textContent = text;
  noticeText.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { noticeText.hidden = true; }, 6000);
}

function updateStats() {
  if (socket && socket.readyState === WebSocket.OPEN) {
    const kbps = Math.round(receivedBytes * 8 / 1000);
    statsText.textContent = `${canvas.width}×${canvas.height} · ${drawnFrames} fps · ${kbps} kbit/s`;
  } else {
    statsText.textContent = '';
  }
  receivedBytes = 0;
  drawnFrames = 0;
}

buttons.start.addEventListener('click', start);
buttons.disconnect.addEventListener('click', disconnect);
buttons.mute.addEventListener('click', () => {
  muted = !muted;
  buttons.mute.textContent = muted ? 'Unmute' : 'Mute';
  buttons.mute.classList.toggle('active', muted);
  updateOutputGain();
  ensureAudioOutput().catch(() => {});
});
buttons.switchCamera.addEventListener('click', () => send({ type: 'switchCamera' }));
buttons.torch.addEventListener('click', () => send({ type: 'torch', on: !(pipelineState && pipelineState.torch) }));
qualitySelect.addEventListener('change', () => send({ type: 'quality', preset: qualitySelect.value }));
buttons.rotate.addEventListener('click', () => {
  rotationOffset = (rotationOffset + 90) % 360;
  localStorage.setItem(ROTATION_KEY, String(rotationOffset));
});
buttons.fullscreen.addEventListener('click', () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else if ($('stage').requestFullscreen) $('stage').requestFullscreen();
});
buttons.logout.addEventListener('click', async () => {
  disconnect();
  try {
    await fetch('/logout', { method: 'POST' });
  } finally {
    location.replace('/login');
  }
});

buttons.talk.addEventListener('pointerdown', (event) => {
  buttons.talk.setPointerCapture(event.pointerId);
  beginTalking();
});
buttons.talk.addEventListener('pointerup', stopTalking);
buttons.talk.addEventListener('pointercancel', stopTalking);
buttons.talk.addEventListener('contextmenu', (event) => event.preventDefault());
buttons.talk.addEventListener('keydown', (event) => {
  if ((event.key === ' ' || event.key === 'Enter') && !event.repeat) beginTalking();
});
buttons.talk.addEventListener('keyup', (event) => {
  if (event.key === ' ' || event.key === 'Enter') stopTalking();
});

// A hidden tab cannot show video, so the phone is told to stop sending (and capturing) it.
document.addEventListener('visibilitychange', () => {
  send({ type: 'pauseVideo', paused: document.hidden });
  if (!document.hidden) waitingForKey = true;
});

setInterval(updateStats, 1000);
