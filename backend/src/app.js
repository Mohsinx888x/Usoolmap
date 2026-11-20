import { randomUUID } from 'node:crypto';
import Fastify from 'fastify';
import { bearerToken, hashInstallId, safeEqual, signDeviceToken, verifyDeviceToken } from './auth.js';
import { EVENT_TYPES } from './events.js';
import { createRateLimiter } from './ratelimit.js';
import { normalize } from './similarity.js';
import { utcDay } from './usage.js';
import { LANGUAGES, findPersonality, resolveLanguage } from './voices.js';

const DAY_MS = 86_400_000;
const MAX_PLAYS_PER_CALL = 200;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

class HttpError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    Object.assign(this, { status, code, extra });
  }
}

// Request schemas. Fastify validates (and coerces query strings) before a handler runs; any
// failure becomes 400 invalid_request in the error handler below.
const language = { type: 'string', pattern: '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$' };
const personality = { type: 'string', minLength: 1, maxLength: 64 };
const humourLevel = { type: 'integer', minimum: 1, maximum: 3 };
const eventTypes = { type: 'array', minItems: 1, maxItems: EVENT_TYPES.length, uniqueItems: true, items: { enum: EVENT_TYPES } };
const uuid = { type: 'string', format: 'uuid' };
const object = (properties, required) => ({ type: 'object', properties, required });

const SCHEMAS = {
  register: { body: object({ installId: { type: 'string', minLength: 8, maxLength: 200 } }, ['installId']) },
  voices: { querystring: object({ language }, ['language']) },
  starter: { querystring: object({ language, personality, humourLevel }, ['language', 'personality', 'humourLevel']) },
  next: {
    body: object(
      { eventTypes, language, personality, humourLevel, have: { type: 'array', maxItems: 2000, items: uuid }, want: { type: 'integer', minimum: 1, maximum: 20 } },
      ['eventTypes', 'language', 'personality', 'humourLevel', 'want'],
    ),
  },
  regenerate: {
    body: object(
      { eventTypes, language, personality, humourLevel, count: { type: 'integer', minimum: 1, maximum: 10 } },
      ['language', 'personality', 'humourLevel', 'count'],
    ),
  },
  audio: { params: object({ id: uuid }, ['id']) },
  history: {
    body: object(
      { plays: { type: 'array', items: object({ clipId: { type: 'string' }, playedAt: { type: 'string' } }, ['clipId', 'playedAt']) } },
      ['plays'],
    ),
  },
  report: {
    body: object(
      { clipId: uuid, text: { type: 'string', minLength: 1, maxLength: 500 }, language, note: { type: 'string', maxLength: 1000 } },
      ['text', 'language', 'note'],
    ),
  },
  pregenerate: {
    body: object(
      { language, personality, humourLevel, perEvent: { type: 'integer', minimum: 1, maximum: 20 } },
      ['language', 'personality', 'humourLevel', 'perEvent'],
    ),
  },
  usage: { querystring: object({ days: { type: 'integer', minimum: 1, maximum: 90, default: 7 } }) },
  pronunciationList: { querystring: object({ language }) },
  pronunciationPut: {
    body: {
      ...object(
        { language, term: { type: 'string', minLength: 1, maxLength: 100 }, spoken: { type: 'string', minLength: 1, maxLength: 200 },
          ipa: { type: 'string', minLength: 1, maxLength: 200 }, reviewed: { type: 'boolean' } },
        ['language', 'term', 'reviewed'],
      ),
      anyOf: [{ required: ['spoken'] }, { required: ['ipa'] }],
    },
  },
  reports: { querystring: object({ status: { type: 'string', maxLength: 20 } }) },
};

/**
 * `bytes=N-`, `bytes=N-M` and `bytes=-N`. A header we cannot parse is ignored (the full body is
 * a valid answer to any range request); a range past the end is reported so the caller gets 416.
 * @returns {{start: number, end: number} | 'unsatisfiable' | null}
 */
export function parseRange(header, size) {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header ?? '');
  if (!match || (match[1] === '' && match[2] === '')) return null;
  if (match[1] === '') {
    const suffix = Number(match[2]);
    return suffix === 0 || size === 0 ? 'unsatisfiable' : { start: Math.max(0, size - suffix), end: size - 1 };
  }
  const start = Number(match[1]);
  const end = match[2] === '' ? size - 1 : Math.min(Number(match[2]), size - 1);
  return start >= size || start > end ? 'unsatisfiable' : { start, end };
}

/**
 * @param {{config: object, store: object, queue: object, blobs: object, usage: object,
 *   now?: () => Date, logger?: import('pino').Logger}} deps `logger` omitted = no request logs
 */
export function buildApp({ config, store, queue, blobs, usage, now = () => new Date(), logger }) {
  // trustProxy: Container Apps ingress terminates TLS and passes the client in X-Forwarded-For.
  const app = Fastify({ trustProxy: true, ...(logger && { loggerInstance: logger }) });
  app.decorateRequest('deviceId', null);
  const limiter = createRateLimiter({
    capacity: config.rateLimitBurst,
    refillPerSecond: config.rateLimitPerMinute / 60,
    now: () => now().getTime(),
  });

  // ---- errors -------------------------------------------------------------------------------
  const sendError = (reply, status, code, message, extra = {}) =>
    reply.code(status).send({ error: { code, message, ...extra } });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof HttpError) {
      if (error.extra.retryAfterSeconds) reply.header('Retry-After', error.extra.retryAfterSeconds);
      return sendError(reply, error.status, error.code, error.message, error.extra);
    }
    // Schema failures, malformed JSON, oversized bodies and the like are the caller's mistake.
    if (error.validation || (error.statusCode >= 400 && error.statusCode < 500)) {
      return sendError(reply, 400, 'invalid_request', error.message);
    }
    request.log.error({ err: error }, 'request failed');
    return sendError(reply, 500, 'internal_error', 'Unexpected server error');
  });
  app.setNotFoundHandler((request, reply) => sendError(reply, 404, 'not_found', 'No such endpoint'));

  // ---- authentication and rate limiting ------------------------------------------------------
  const rateLimit = (key) => {
    const { ok, retryAfterSeconds } = limiter.take(key);
    if (!ok) throw new HttpError(429, 'rate_limited', 'Too many requests; slow down', { retryAfterSeconds });
  };

  const requireAppKey = async (request) => {
    if (!safeEqual(request.headers['x-app-key'], config.appKey)) throw new HttpError(401, 'unauthorized', 'Invalid app key');
    rateLimit(`ip:${request.ip}`); // registration has no device yet, so it is limited per address
  };
  const requireAdmin = async (request) => {
    if (!safeEqual(request.headers['x-admin-key'], config.adminKey)) throw new HttpError(401, 'unauthorized', 'Invalid admin key');
  };
  const requireDevice = async (request) => {
    const deviceId = verifyDeviceToken(bearerToken(request.headers.authorization), config.tokenSecret);
    // The signature proves we issued the token; the lookup rejects devices that no longer exist.
    if (!deviceId || !(await store.deviceExists(deviceId))) throw new HttpError(401, 'unauthorized', 'Invalid device token');
    request.deviceId = deviceId;
  };
  const requireDeviceLimited = async (request) => {
    await requireDevice(request);
    rateLimit(`device:${request.deviceId}`);
  };

  // ---- shared helpers ------------------------------------------------------------------------
  const clipJson = (clip, fallbackVoice) => ({
    id: clip.id, eventType: clip.eventType, language: clip.language, personality: clip.personality,
    humourLevel: clip.humourLevel, text: clip.text, bytes: clip.bytes, voice: clip.voice,
    // True when this caller's language has no voice of its own and a related one was used.
    fallbackVoice: clip.fallbackVoice || fallbackVoice,
    audioUrl: `/v1/clips/${clip.id}/audio`,
    createdAt: clip.createdAt.toISOString().replace(/\.\d{3}Z$/, 'Z'),
  });

  /** Resolves the language and checks that the personality belongs to it. */
  const selection = ({ language: requested, personality: personalityId, humourLevel: level }) => {
    const resolved = resolveLanguage(requested);
    if (!findPersonality(resolved.language, personalityId)) {
      throw new HttpError(400, 'invalid_request', `Unknown personality "${personalityId}" for language ${resolved.language}`);
    }
    return { language: resolved.language, personality: personalityId, humourLevel: level, fallback: resolved.fallback };
  };

  const quotaRemaining = async (deviceId) =>
    Math.max(0, config.dailyDeviceQuota - (await store.getQuotaUsed(deviceId, utcDay(now()))));
  const secondsToUtcMidnight = () => Math.ceil((DAY_MS - (now().getTime() % DAY_MS)) / 1000);

  /**
   * Stores job rows and puts them on the queue. Stops at the first queue failure so that no
   * pending row is left without a message. @returns {Promise<number>} jobs actually queued
   */
  async function enqueue(jobs) {
    let queued = 0;
    for (const job of jobs) {
      const row = { id: randomUUID(), status: 'pending', createdAt: now(), ...job };
      await store.insertJob(row);
      try {
        await queue.send({ jobId: row.id });
      } catch (error) {
        app.log.error({ err: error.message }, 'could not queue a generation job');
        await store.updateJob(row.id, { status: 'failed', error: 'generation_unavailable: queue send failed', finishedAt: now() });
        break;
      }
      queued++;
    }
    return queued;
  }

  const requireBudget = async () => {
    if (await usage.isOverBudget()) throw new HttpError(503, 'budget_exceeded', 'The generation budget for today is used up');
  };
  const requireQueued = (queued) => {
    if (queued === 0) throw new HttpError(503, 'generation_unavailable', 'Generation jobs cannot be queued right now');
  };

  // ---- public and device routes --------------------------------------------------------------
  app.get('/healthz', async () => ({ ok: true }));

  app.post('/v1/devices/register', { schema: SCHEMAS.register, onRequest: requireAppKey }, async (request) => {
    const deviceId = await store.upsertDevice(randomUUID(), hashInstallId(request.body.installId, config.tokenSecret));
    return { deviceId, token: signDeviceToken(deviceId, config.tokenSecret) };
  });

  app.get('/v1/voices', { schema: SCHEMAS.voices, onRequest: requireDeviceLimited }, async (request) => {
    const resolved = resolveLanguage(request.query.language);
    return {
      ...resolved,
      personalities: resolved.personalities.map(({ id, label, voice, gender, description }) => ({ id, label, voice, gender, description })),
    };
  });

  app.get('/v1/packs/starter', { schema: SCHEMAS.starter, onRequest: requireDeviceLimited }, async (request) => {
    const { fallback, ...wanted } = selection(request.query);
    // Oldest first, so every phone gets the same pack and it stays cacheable. An empty library
    // gives an empty pack: nothing is invented here.
    const clips = await store.findClips({ ...wanted, eventTypes: EVENT_TYPES, perEvent: config.starterPerEvent });
    return {
      clipCount: clips.length,
      totalBytes: clips.reduce((sum, clip) => sum + clip.bytes, 0),
      clips: clips.map((clip) => clipJson(clip, fallback)),
    };
  });

  app.post('/v1/clips/next', { schema: SCHEMAS.next, onRequest: requireDeviceLimited }, async (request) => {
    const { deviceId } = request;
    const { eventTypes: wantedEvents, have = [], want } = request.body;
    const { fallback, ...wanted } = selection(request.body);

    const since = new Date(now().getTime() - config.nonRepeatDays * DAY_MS);
    const available = await store.findClips({
      ...wanted, eventTypes: wantedEvents, perEvent: want, excludeIds: have, unseenBy: { deviceId, since },
    });
    const byEvent = Map.groupBy(available, (clip) => clip.eventType);

    // `want` is the total for the response; event types take turns so each gets a fair share.
    const clips = [];
    for (let round = 0; round < want && clips.length < want; round++) {
      for (const eventType of wantedEvents) {
        const clip = byEvent.get(eventType)?.[round];
        if (clip && clips.length < want) clips.push(clip);
      }
    }
    await store.recordPlays(deviceId, 'served', clips.map((clip) => ({ clipId: clip.id, at: now() })));

    // An event type is short when it could not supply its share. Worst shortfall first.
    const share = Math.ceil(want / wantedEvents.length);
    const short = wantedEvents
      .map((eventType) => ({ eventType, missing: share - (byEvent.get(eventType)?.length ?? 0) }))
      .filter((entry) => entry.missing > 0)
      .sort((a, b) => b.missing - a.missing);

    let generating = 0;
    if (short.length > 0 && !(await usage.isOverBudget())) {
      const pendingSince = new Date(now().getTime() - config.pendingJobTtlMinutes * 60_000);
      const jobs = [];
      for (const { eventType } of short) {
        if (jobs.length >= config.maxJobsPerRequest) break;
        const job = { ...wanted, eventType, requestedBy: deviceId, forceNew: false };
        if (!(await store.hasPendingJob(job, pendingSince))) jobs.push(job);
      }
      // Quota is taken only for jobs that will really be queued, never for skipped duplicates.
      const granted = jobs.length > 0 ? await store.consumeQuota(deviceId, utcDay(now()), jobs.length, config.dailyDeviceQuota) : 0;
      generating = await enqueue(jobs.slice(0, granted));
    }
    return { clips: clips.map((clip) => clipJson(clip, fallback)), generating, quotaRemaining: await quotaRemaining(deviceId) };
  });

  app.post('/v1/clips/regenerate', { schema: SCHEMAS.regenerate, onRequest: requireDeviceLimited }, async (request, reply) => {
    const { deviceId } = request;
    const { eventTypes: wantedEvents = EVENT_TYPES, count } = request.body;
    const { fallback, ...wanted } = selection(request.body);
    await requireBudget();

    const granted = await store.consumeQuota(deviceId, utcDay(now()), count, config.dailyDeviceQuota);
    if (granted === 0) {
      throw new HttpError(429, 'quota_exceeded', 'Daily generation quota used up', { retryAfterSeconds: secondsToUtcMidnight() });
    }
    // Spread over the event types from a random starting point, so repeated small requests
    // do not always refresh the same event.
    const offset = Math.floor(Math.random() * wantedEvents.length);
    const jobs = Array.from({ length: granted }, (_, i) => ({
      ...wanted, eventType: wantedEvents[(offset + i) % wantedEvents.length], requestedBy: deviceId, forceNew: true,
    }));
    const queued = await enqueue(jobs);
    requireQueued(queued);
    return reply.code(202).send({ queued, quotaRemaining: await quotaRemaining(deviceId) });
  });

  // Not rate limited: after one /clips/next the phone downloads up to twenty of these at once.
  app.get('/v1/clips/:id/audio', { schema: SCHEMAS.audio, onRequest: requireDevice }, async (request, reply) => {
    const clip = await store.getClip(request.params.id);
    if (!clip || clip.status !== 'ready') throw new HttpError(404, 'not_found', 'No such clip');

    const range = parseRange(request.headers.range, clip.bytes);
    if (range === 'unsatisfiable') {
      reply.header('Content-Range', `bytes */${clip.bytes}`);
      return sendError(reply, 416, 'invalid_range', 'Requested range is outside the clip');
    }
    const { start, end } = range ?? { start: 0, end: clip.bytes - 1 };
    const stream = await blobs.read(clip.blobPath, start, end - start + 1);
    if (!stream) throw new HttpError(404, 'not_found', 'Clip audio is missing');

    if (range) reply.code(206).header('Content-Range', `bytes ${start}-${end}/${clip.bytes}`);
    return reply
      .header('Content-Type', 'audio/mpeg')
      .header('Content-Length', end - start + 1)
      .header('Accept-Ranges', 'bytes')
      .header('Cache-Control', 'private, max-age=31536000, immutable') // a clip never changes
      .send(stream);
  });

  app.post('/v1/history', { schema: SCHEMAS.history, onRequest: requireDeviceLimited }, async (request) => {
    const latest = new Map(); // clipId -> most recent play, never later than now
    for (const { clipId, playedAt } of request.body.plays.slice(0, MAX_PLAYS_PER_CALL)) {
      const at = new Date(Math.min(Date.parse(playedAt), now().getTime()));
      if (!UUID.test(clipId) || Number.isNaN(at.getTime())) continue;
      const id = clipId.toLowerCase();
      if (!(latest.get(id) > at)) latest.set(id, at);
    }
    const entries = [...latest].map(([clipId, at]) => ({ clipId, at }));
    return { accepted: await store.recordPlays(request.deviceId, 'played', entries) };
  });

  app.post('/v1/pronunciation/reports', { schema: SCHEMAS.report, onRequest: requireDeviceLimited }, async (request, reply) => {
    const { clipId, text, language: reportLanguage, note } = request.body;
    // A report about a clip we no longer know is still worth keeping; only the link is dropped.
    const clip = clipId ? await store.getClip(clipId) : null;
    const id = randomUUID();
    await store.insertReport({ id, deviceId: request.deviceId, clipId: clip?.id ?? null, text, language: reportLanguage, note, createdAt: now() });
    return reply.code(201).send({ id });
  });

  // ---- admin routes --------------------------------------------------------------------------
  app.post('/v1/admin/pregenerate', { schema: SCHEMAS.pregenerate, onRequest: requireAdmin }, async (request, reply) => {
    if (!LANGUAGES[request.body.language]) {
      throw new HttpError(400, 'invalid_request', `No voice for ${request.body.language}; pregenerate needs a supported language`);
    }
    const { fallback, ...wanted } = selection(request.body);
    await requireBudget();
    const jobs = EVENT_TYPES.flatMap((eventType) =>
      Array.from({ length: request.body.perEvent }, () => ({ ...wanted, eventType, requestedBy: null, forceNew: true })),
    );
    const queued = await enqueue(jobs);
    requireQueued(queued);
    return reply.code(202).send({ queued });
  });

  app.get('/v1/admin/usage', { schema: SCHEMAS.usage, onRequest: requireAdmin }, async (request) => usage.report(request.query.days));

  const entryJson = ({ id, language: entryLanguage, term, spoken, ipa, reviewed, updatedAt }) => ({
    id, language: entryLanguage, term, spoken, ipa, reviewed, updatedAt,
  });

  app.get('/v1/admin/pronunciation', { schema: SCHEMAS.pronunciationList, onRequest: requireAdmin }, async (request) => {
    const entries = await store.listPronunciation(request.query.language ? [request.query.language] : null);
    return { entries: entries.map(entryJson) };
  });

  app.put('/v1/admin/pronunciation', { schema: SCHEMAS.pronunciationPut, onRequest: requireAdmin }, async (request) => {
    const { language: entryLanguage, term, spoken = null, ipa = null, reviewed } = request.body;
    const normalizedTerm = normalize(term);
    if (!normalizedTerm) throw new HttpError(400, 'invalid_request', 'term must contain letters or digits');
    const entry = await store.upsertPronunciation({
      language: entryLanguage, term: term.trim(), normalizedTerm, spoken, ipa, reviewed, updatedAt: now(),
    });
    return entryJson(entry);
  });

  app.get('/v1/admin/pronunciation/reports', { schema: SCHEMAS.reports, onRequest: requireAdmin }, async (request) => ({
    reports: await store.listReports(request.query.status ?? null),
  }));

  return app;
}
