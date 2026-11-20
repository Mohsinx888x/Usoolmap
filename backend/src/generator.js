// The generation pipeline: budget check -> prompt -> text -> validation -> dedupe -> SSML ->
// speech -> blob -> database. Every external service is injected, so tests run it with fakes.

import { randomUUID } from 'node:crypto';
import { isArabic, tashkeelRatio } from './arabic.js';
import { EVENTS } from './events.js';
import { buildPrompt } from './prompt.js';
import { buildSsml } from './pronunciation.js';
import { normalize, openingPhrase, similarity } from './similarity.js';
import { findPersonality } from './voices.js';

const MAX_TEXT_ATTEMPTS = 3;
const PROMPT_RECENT = 40; // recent jokes and openings shown to the model
const OPENING_WINDOW = 15; // an opening may not repeat within this many clips
const SIMILARITY_WINDOW = 300; // most recent clips compared for near-duplicates
const MAX_CHARS = 300;
const MAX_CHARS_ARABIC = 480; // tashkeel roughly adds one mark per letter
const MIN_TASHKEEL_RATIO = 0.4;

const DIGITS = '[0-9\\u0660-\\u0669\\u06F0-\\u06F9]';
const UNITS = 'km|kms|m|mi|miles?|meters?|metres?|kilometers?|kilometres?|ft|feet|foot|yards?|yd|mph|kmh|km/h|كم|كيلو|كيلومتر|متر|م|ميل';
const DIGITS_WITH_UNIT = new RegExp(`${DIGITS}+\\s*(?:${UNITS})(?![\\p{L}])`, 'iu');
const ANY_DIGIT = new RegExp(DIGITS, 'u');

/** A failure that retrying the same job will not fix; `code` is stable and stored on the job. */
export class GenerationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'GenerationError';
    this.code = code;
  }
}

/** Models often wrap the line in quotes or add line breaks; neither should be spoken. */
const clean = (raw) =>
  String(raw ?? '').replace(/\s+/g, ' ').trim().replace(/^["'“”«»‘’]+|["'“”«»‘’]+$/g, '').trim();

/** @returns {string | null} why the text is unusable, or null when it is fine */
export function validateText(text, language) {
  const arabic = isArabic(language);
  const limit = arabic ? MAX_CHARS_ARABIC : MAX_CHARS;
  if (text.length === 0) return 'empty reply';
  if (text.length > limit) return `too long (${text.length} characters, limit ${limit})`;
  if (DIGITS_WITH_UNIT.test(text)) return 'contains a distance or speed written as digits with a unit';
  if (arabic && ANY_DIGIT.test(text)) return 'contains digits; numbers must be written as words';
  if (arabic && tashkeelRatio(text) < MIN_TASHKEEL_RATIO) return 'not fully vowelled with tashkeel';
  return null;
}

/**
 * @param {{store: object, textGenerator: {generate: Function}, speech: {synthesize: Function},
 *   blobs: object, usage: object, config: object, now?: () => Date}} deps
 */
export function createGenerator({ store, textGenerator, speech, blobs, usage, config, now = () => new Date() }) {
  /** Lexical checks only (see similarity.js): same text, same opening, or high shingle overlap. */
  async function duplicateReason(text, { eventType, language }, recent) {
    const normalized = normalize(text);
    if (await store.hasClipText(eventType, language, normalized)) return 'identical to an existing line';
    const opening = openingPhrase(text);
    if (recent.slice(0, OPENING_WINDOW).some((clip) => clip.opening === opening)) {
      return `starts with "${opening}", an opening used recently`;
    }
    const tooSimilar = recent.some((clip) => similarity(normalized, clip.normalizedText) >= config.similarityThreshold);
    return tooSimilar ? 'too similar in wording to an existing line' : null;
  }

  async function writeText(job, recent) {
    const shown = recent.slice(0, PROMPT_RECENT);
    const rejected = [];
    for (let attempt = 1; attempt <= MAX_TEXT_ATTEMPTS; attempt++) {
      const prompt = buildPrompt({
        ...job,
        recentTexts: shown.map((clip) => clip.text),
        recentOpenings: [...new Set(shown.map((clip) => clip.opening))],
        rejected,
      });
      const text = clean(await textGenerator.generate(prompt));
      await usage.record({ grokCalls: 1 }); // counted even when rejected: the call was paid for
      const reason = validateText(text, job.language) ?? (await duplicateReason(text, job, recent));
      if (!reason) return text;
      rejected.push({ text: text.slice(0, 200), reason });
    }
    const reasons = rejected.map((r) => r.reason).join('; ');
    throw new GenerationError('no_acceptable_text', `No acceptable text after ${MAX_TEXT_ATTEMPTS} attempts: ${reasons}`);
  }

  async function run(job) {
    const { eventType, language, humourLevel } = job;
    const personality = findPersonality(language, job.personality);
    if (!EVENTS[eventType] || !personality) {
      throw new GenerationError('invalid_job', `Unknown event type, language or personality: ${eventType}/${language}/${job.personality}`);
    }
    if (await usage.isOverBudget()) {
      throw new GenerationError('budget_exceeded', 'The estimated spend for today has reached DAILY_BUDGET_USD');
    }

    // A job queued because one phone ran short is pointless if another job has filled the
    // library in the meantime: reuse what is there instead of paying for a new clip.
    if (!job.forceNew && job.requestedBy) {
      const since = new Date(now().getTime() - config.nonRepeatDays * 86_400_000);
      const [existing] = await store.findClips({
        eventTypes: [eventType], language, personality: personality.id, humourLevel, perEvent: 1,
        unseenBy: { deviceId: job.requestedBy, since },
      });
      if (existing) return existing;
    }

    const recent = await store.recentClips(eventType, language, SIMILARITY_WINDOW);
    const text = await writeText(job, recent);

    const base = language.split('-')[0];
    const entries = await store.listPronunciation([language, base], true);
    const ssml = buildSsml({ text, language, voice: personality.voice, rate: personality.rate, pitch: personality.pitch, entries });
    const audio = await speech.synthesize(ssml);
    // Estimate: Azure bills the text plus most SSML markup, so this slightly under-counts.
    await usage.record({ ttsCharacters: [...text].length });

    const id = randomUUID();
    const blobPath = `clips/${language}/${eventType}/${id}.mp3`;
    await blobs.put(blobPath, audio, 'audio/mpeg');

    const clip = {
      id, eventType, language, personality: personality.id, humourLevel, text,
      normalizedText: normalize(text), opening: openingPhrase(text), voice: personality.voice,
      // Clips are stored under a language that has its own voice (the API resolves the tag
      // before queueing), so the stored voice is never a stand-in.
      fallbackVoice: false,
      blobPath, bytes: audio.length, status: 'ready', source: 'generated', createdAt: now(),
    };
    await store.insertClip(clip);
    await usage.record({ bytesStored: audio.length });
    return clip;
  }

  return {
    /**
     * Runs one job and records the outcome on its row (when the job has an id). A
     * GenerationError is final; any other error is left for the worker to retry.
     * @param {{id?: string, eventType: string, language: string, personality: string,
     *   humourLevel: number, requestedBy: string | null, forceNew: boolean}} job
     */
    async generate(job) {
      const record = (patch) => (job.id ? store.updateJob(job.id, patch) : undefined);
      try {
        const clip = await run(job);
        await record({ status: 'done', error: null, finishedAt: now() });
        return clip;
      } catch (error) {
        const message = `${error.code ?? 'error'}: ${error.message}`.slice(0, 500);
        await record(error instanceof GenerationError ? { status: 'failed', error: message, finishedAt: now() } : { error: message });
        throw error;
      }
    },
  };
}
