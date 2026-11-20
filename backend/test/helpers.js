// Fakes and fixtures shared by the test files. Nothing here talks to a real service.

import { randomUUID } from 'node:crypto';
import { buildApp } from '../src/app.js';
import { createMemoryBlobStore } from '../src/blob.js';
import { loadConfig } from '../src/config.js';
import { createGenerator } from '../src/generator.js';
import { createMemoryJobQueue } from '../src/queue.js';
import { normalize, openingPhrase } from '../src/similarity.js';
import { createMemoryStore } from '../src/store/memory.js';
import { createUsage } from '../src/usage.js';
import { createWorker } from '../src/worker.js';

export const APP_KEY = 'test-app-key';
export const ADMIN_KEY = 'test-admin-key';

export const testConfig = (overrides = {}) => ({
  ...loadConfig({ TOKEN_SECRET: 'test-token-secret', APP_KEY, ADMIN_KEY }),
  ...overrides,
});

/** TextGenerator that replies with the given texts in order and records every prompt. */
export function scriptedTextGenerator(texts = []) {
  const script = [...texts];
  const prompts = [];
  return {
    prompts,
    push: (...more) => void script.push(...more),
    async generate(prompt) {
      prompts.push(prompt);
      if (script.length === 0) throw new Error('scripted text generator has nothing left to say');
      const next = script.shift();
      if (next instanceof Error) throw next;
      return next;
    },
  };
}

/** SpeechSynthesizer whose "mp3" is derived from the SSML, so tests can tell clips apart. */
export function fakeSpeech() {
  const ssml = [];
  return {
    ssml,
    async synthesize(document) {
      ssml.push(document);
      return Buffer.from(`FAKE-MP3:${document}`);
    },
  };
}

export const silentLogger = { info() {}, warn() {}, error() {} };

// Distinct English lines: different openings, little shared wording.
export const JOKES = [
  'Buckle up, the road has no idea what is coming.',
  'Somewhere a parking space is already getting nervous about us.',
  'My circuits predict snacks at the end of this journey.',
  'Great trips begin exactly like this, usually with fewer wrong turns.',
  'Engines humming, playlist ready, dignity optional today.',
  'Adventure called and for once we actually picked up.',
];

// Fully vowelled colloquial-style Arabic lines for the tashkeel checks.
export const ARABIC_JOKES = [
  'يَا هَلَا وَاللهْ، شَكْلِنَا بِنْلِفْ الدِّيرَةْ كِلَّهَا قَبِلْ لَا نُوصَلْ',
  'تَوَكَّلْنَا، وَالسَّيَّارَةْ مِسْتَانْسَةْ أَكْثَرْ مِنَّا الْيُومْ',
];

/** Whole backend wired with in-memory fakes and a clock the test controls. */
export function createTestBackend({ config: overrides = {}, store = createMemoryStore() } = {}) {
  const clock = { ms: Date.parse('2026-11-20T10:00:00Z') };
  const now = () => new Date(clock.ms);
  const config = testConfig(overrides);
  const queue = createMemoryJobQueue({ now: () => clock.ms });
  const blobs = createMemoryBlobStore();
  const usage = createUsage({ store, config, now });
  const textGenerator = scriptedTextGenerator();
  const speech = fakeSpeech();
  const generator = createGenerator({ store, textGenerator, speech, blobs, usage, config, now });
  const worker = createWorker({ queue, store, generator, config, logger: silentLogger, now });
  const app = buildApp({ config, store, queue, blobs, usage, textGenerator, now });

  /** Puts a ready clip straight into the library, one minute after the previous one. */
  async function seedClip(fields = {}) {
    clock.ms += 60_000;
    const id = randomUUID();
    const text = fields.text ?? `Seeded line ${id}`;
    const audio = Buffer.from(`AUDIO-${id}-0123456789`);
    const clip = {
      id, eventType: 'route_start', language: 'en-US', personality: 'andy', humourLevel: 2, text,
      normalizedText: normalize(text), opening: openingPhrase(text), voice: 'en-US-AndrewMultilingualNeural',
      fallbackVoice: false, blobPath: `clips/en-US/route_start/${id}.mp3`, bytes: audio.length,
      status: 'ready', source: 'generated', createdAt: now(), ...fields,
    };
    await blobs.put(clip.blobPath, audio, 'audio/mpeg');
    await store.insertClip(clip);
    return { ...clip, audio };
  }

  async function registerDevice(installId = `install-${randomUUID()}`) {
    const response = await app.inject({ method: 'POST', url: '/v1/devices/register', headers: { 'x-app-key': APP_KEY }, payload: { installId } });
    const { deviceId, token } = response.json();
    return { deviceId, token, headers: { authorization: `Bearer ${token}` } };
  }

  return { clock, now, config, store, queue, blobs, usage, textGenerator, speech, generator, worker, app, seedClip, registerDevice };
}
