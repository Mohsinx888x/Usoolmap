import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { EVENT_TYPES } from '../src/events.js';
import { ADMIN_KEY, APP_KEY, JOKES, createTestBackend } from './helpers.js';

const admin = { 'x-admin-key': ADMIN_KEY };
const selection = { language: 'en-US', personality: 'andy', humourLevel: 2 };

const post = (backend, url, headers, payload) => backend.app.inject({ method: 'POST', url, headers, payload });
const get = (backend, url, headers) => backend.app.inject({ method: 'GET', url, headers });
const next = (backend, device, body = {}) =>
  post(backend, '/v1/clips/next', device.headers, { eventTypes: ['route_start'], ...selection, want: 5, ...body });
const errorCode = (response) => response.json().error.code;

test('healthz needs no authentication', async () => {
  const backend = createTestBackend();
  const response = await get(backend, '/healthz');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { ok: true });
});

test('register: idempotent per install id, needs the app key, validates the body', async () => {
  const backend = createTestBackend();
  const register = (headers, payload) => post(backend, '/v1/devices/register', headers, payload);
  const first = await register({ 'x-app-key': APP_KEY }, { installId: 'install-aaaaaaaa' });
  const again = await register({ 'x-app-key': APP_KEY }, { installId: 'install-aaaaaaaa' });
  const other = await register({ 'x-app-key': APP_KEY }, { installId: 'install-bbbbbbbb' });
  assert.equal(first.statusCode, 200);
  assert.match(first.json().deviceId, /^[0-9a-f-]{36}$/);
  assert.deepEqual(again.json(), first.json());
  assert.notEqual(other.json().deviceId, first.json().deviceId);

  for (const headers of [{}, { 'x-app-key': 'wrong' }, { 'x-app-key': ADMIN_KEY }]) {
    const refused = await register(headers, { installId: 'install-aaaaaaaa' });
    assert.equal(refused.statusCode, 401);
    assert.equal(errorCode(refused), 'unauthorized');
  }
  for (const payload of [{}, { installId: 'short' }, { installId: null }]) {
    const invalid = await register({ 'x-app-key': APP_KEY }, payload);
    assert.equal(invalid.statusCode, 400);
    assert.equal(errorCode(invalid), 'invalid_request');
  }
});

test('device routes reject missing, forged and admin credentials', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const forged = `${device.token.slice(0, -2)}xx`;
  for (const headers of [{}, { authorization: `Bearer ${forged}` }, { authorization: device.token }, admin, { 'x-app-key': APP_KEY }]) {
    const response = await get(backend, '/v1/voices?language=en-US', headers);
    assert.equal(response.statusCode, 401, JSON.stringify(headers));
  }
  assert.equal((await get(backend, '/v1/voices?language=en-US', device.headers)).statusCode, 200);
});

test('voices: exact language, fallback with a plain note, validation', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const qatar = (await get(backend, '/v1/voices?language=ar-QA', device.headers)).json();
  assert.deepEqual([qatar.requestedLanguage, qatar.language, qatar.fallback, qatar.fallbackNote], ['ar-QA', 'ar-QA', false, null]);
  assert.deepEqual(Object.keys(qatar.personalities[0]), ['id', 'label', 'voice', 'gender', 'description']);
  assert.deepEqual(qatar.personalities.map((p) => [p.id, p.voice, p.gender]), [
    ['abu-nasser', 'ar-QA-MoazNeural', 'male'], ['noura', 'ar-QA-AmalNeural', 'female'], ['al-shayeb', 'ar-QA-MoazNeural', 'male'],
  ]);

  const jordan = (await get(backend, '/v1/voices?language=ar-JO', device.headers)).json();
  assert.deepEqual([jordan.requestedLanguage, jordan.language, jordan.fallback], ['ar-JO', 'ar-SA', true]);
  assert.equal(jordan.fallbackNote, 'No voice for ar-JO; using the Saudi Arabic voice.');

  for (const url of ['/v1/voices', '/v1/voices?language=', '/v1/voices?language=%3Cscript%3E']) {
    assert.equal((await get(backend, url, device.headers)).statusCode, 400, url);
  }
});

test('next: returns unseen clips in the contract shape and never repeats them within the window', async () => {
  const backend = createTestBackend({ config: { nonRepeatDays: 90 } });
  const device = await backend.registerDevice();
  const seeded = [await backend.seedClip(), await backend.seedClip(), await backend.seedClip()];
  await backend.seedClip({ personality: 'ava', voice: 'en-US-AvaMultilingualNeural' }); // other personality
  await backend.seedClip({ humourLevel: 3 }); // other humour level
  await backend.seedClip({ eventType: 'arrival' }); // other event
  await backend.seedClip({ status: 'pending' }); // not ready

  const first = await next(backend, device, { want: 2 });
  assert.equal(first.statusCode, 200);
  const body = first.json();
  assert.deepEqual(body.clips.map((c) => c.id), [seeded[0].id, seeded[1].id]);
  assert.deepEqual(body.clips[0], {
    id: seeded[0].id, eventType: 'route_start', language: 'en-US', personality: 'andy', humourLevel: 2,
    text: seeded[0].text, bytes: seeded[0].audio.length, voice: 'en-US-AndrewMultilingualNeural', fallbackVoice: false,
    audioUrl: `/v1/clips/${seeded[0].id}/audio`, createdAt: '2026-11-20T10:01:00Z',
  });

  const second = (await next(backend, device, { want: 2 })).json();
  assert.deepEqual(second.clips.map((c) => c.id), [seeded[2].id], 'served clips are not served again');
  assert.deepEqual((await next(backend, device)).json().clips, []);

  // Another device still gets everything.
  const other = await backend.registerDevice();
  assert.equal((await next(backend, other)).json().clips.length, 3);

  // Just inside the window: still nothing. After it: the clips may come back.
  backend.clock.ms += 89 * 86_400_000;
  assert.deepEqual((await next(backend, device)).json().clips, []);
  backend.clock.ms += 2 * 86_400_000;
  assert.equal((await next(backend, device)).json().clips.length, 3);
});

test('next: a clip reported as played stays away for the window even if it was never served', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const [a, b] = [await backend.seedClip(), await backend.seedClip()];
  await post(backend, '/v1/history', device.headers, { plays: [{ clipId: a.id, playedAt: backend.now().toISOString() }] });
  assert.deepEqual((await next(backend, device)).json().clips.map((c) => c.id), [b.id]);
});

test('next: respects `have` and shares `want` between event types', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const starts = [await backend.seedClip(), await backend.seedClip(), await backend.seedClip()];
  const arrivals = [await backend.seedClip({ eventType: 'arrival' }), await backend.seedClip({ eventType: 'arrival' })];

  const response = await next(backend, device, { eventTypes: ['route_start', 'arrival'], have: [starts[0].id], want: 3 });
  assert.deepEqual(response.json().clips.map((c) => c.id), [starts[1].id, arrivals[0].id, starts[2].id]);

  // The clip in `have` was not served, so it is still available once the phone no longer has it.
  const later = await next(backend, device, { eventTypes: ['route_start'], want: 3 });
  assert.deepEqual(later.json().clips.map((c) => c.id), [starts[0].id]);
});

test('next: queues jobs when the library is short, without duplicating a pending job', async () => {
  const backend = createTestBackend({ config: { dailyDeviceQuota: 20, maxJobsPerRequest: 3 } });
  const device = await backend.registerDevice();
  await backend.seedClip();

  const first = (await next(backend, device, { want: 2 })).json();
  assert.equal(first.clips.length, 1);
  assert.deepEqual([first.generating, first.quotaRemaining], [1, 19]);
  assert.equal(backend.queue.size(), 1);

  // Same combination again, from this and from another device: the pending job is enough.
  const again = (await next(backend, device, { want: 2 })).json();
  assert.deepEqual([again.clips.length, again.generating, again.quotaRemaining], [0, 0, 19]);
  const other = await backend.registerDevice();
  assert.deepEqual([(await next(backend, other, { want: 5 })).json().generating, backend.queue.size()], [0, 1]);

  // A different combination is a different job.
  assert.equal((await next(backend, device, { humourLevel: 3 })).json().generating, 1);
  assert.equal(backend.queue.size(), 2);

  // Once the worker has finished the job, a new one may be queued, and the new clip is served.
  backend.textGenerator.push(JOKES[0], JOKES[1]);
  await backend.worker.runOnce();
  await backend.worker.runOnce();
  const afterWork = (await next(backend, device, { want: 2 })).json();
  assert.deepEqual(afterWork.clips.map((c) => c.text), [JOKES[0]]);
  assert.equal(afterWork.generating, 1);
});

test('next: at most MAX_JOBS_PER_REQUEST jobs, and a stale pending job stops blocking', async () => {
  const backend = createTestBackend({ config: { maxJobsPerRequest: 3, pendingJobTtlMinutes: 30 } });
  const device = await backend.registerDevice();
  const all = (await next(backend, device, { eventTypes: EVENT_TYPES, want: 20 })).json();
  assert.deepEqual([all.clips.length, all.generating, all.quotaRemaining], [0, 3, 17]);

  backend.clock.ms += 31 * 60_000;
  const later = (await next(backend, device, { eventTypes: EVENT_TYPES.slice(0, 3), want: 3 })).json();
  assert.equal(later.generating, 3);
});

test('next: an exhausted quota still returns existing clips, with generating 0', async () => {
  const backend = createTestBackend({ config: { dailyDeviceQuota: 2 } });
  const device = await backend.registerDevice();
  const clip = await backend.seedClip({ eventType: 'u_turn' });

  const first = (await next(backend, device, { eventTypes: EVENT_TYPES, want: 20 })).json();
  assert.deepEqual([first.clips.length, first.generating, first.quotaRemaining], [1, 2, 0]);
  assert.equal(backend.queue.size(), 2);

  const second = await next(backend, device, { eventTypes: ['roundabout', 'u_turn'], have: [], want: 4 });
  assert.equal(second.statusCode, 200);
  assert.deepEqual(second.json(), { clips: [], generating: 0, quotaRemaining: 0 });
  assert.equal(backend.queue.size(), 2);

  const other = await backend.registerDevice();
  const theirs = (await next(backend, other, { eventTypes: ['u_turn'], want: 1 })).json();
  assert.deepEqual([theirs.clips[0].id, theirs.generating, theirs.quotaRemaining], [clip.id, 0, 2]);

  // The quota is per UTC day.
  backend.clock.ms += 86_400_000;
  assert.equal((await next(backend, device, { eventTypes: ['roundabout'], want: 1 })).json().quotaRemaining, 1);
});

test('next: nothing is queued once the daily budget is spent', async () => {
  const backend = createTestBackend({ config: { dailyBudgetUsd: 0 } });
  const device = await backend.registerDevice();
  assert.deepEqual((await next(backend, device)).json(), { clips: [], generating: 0, quotaRemaining: 20 });
  assert.equal(backend.queue.size(), 0);
});

test('next: a language without its own voice uses the fallback library and says so', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const clip = await backend.seedClip({ language: 'ar-SA', personality: 'abu-fahad', voice: 'ar-SA-HamedNeural' });
  const body = (await next(backend, device, { language: 'ar-JO', personality: 'abu-fahad', want: 1 })).json();
  assert.deepEqual([body.clips[0].id, body.clips[0].language, body.clips[0].fallbackVoice], [clip.id, 'ar-SA', true]);
});

test('next: validation errors are 400 invalid_request', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const bad = [
    { eventTypes: ['teleport'] }, { eventTypes: [] }, { eventTypes: undefined }, { want: 0 }, { want: 21 }, { want: 'many' },
    { humourLevel: 4 }, { personality: 'nobody' }, { personality: 'abu-nasser' }, { language: 'english!' }, { have: ['not-a-uuid'] },
  ];
  for (const override of bad) {
    const response = await next(backend, device, override);
    assert.equal(response.statusCode, 400, JSON.stringify(override));
    assert.equal(errorCode(response), 'invalid_request');
    assert.ok(response.json().error.message);
  }
  const malformed = await backend.app.inject({
    method: 'POST', url: '/v1/clips/next', headers: { ...device.headers, 'content-type': 'application/json' }, payload: '{"eventTypes":',
  });
  assert.equal(malformed.statusCode, 400);
  assert.equal(errorCode(malformed), 'invalid_request');
  assert.equal(backend.queue.size(), 0);
});

test('rate limit: a burst from one device gets 429 rate_limited with retryAfterSeconds', async () => {
  const backend = createTestBackend({ config: { rateLimitBurst: 3, rateLimitPerMinute: 6 } });
  const device = await backend.registerDevice();
  const other = await backend.registerDevice();
  const statuses = [];
  for (let i = 0; i < 4; i++) statuses.push((await next(backend, device)).statusCode);
  assert.deepEqual(statuses, [200, 200, 200, 429]);

  const limited = await next(backend, device);
  assert.deepEqual(limited.json().error, { code: 'rate_limited', message: 'Too many requests; slow down', retryAfterSeconds: 10 });
  assert.equal(limited.headers['retry-after'], '10');
  assert.equal((await next(backend, other)).statusCode, 200, 'other devices are unaffected');

  backend.clock.ms += 10_000;
  assert.equal((await next(backend, device)).statusCode, 200);
});

test('regenerate: always queues forceNew jobs, limited by the quota', async () => {
  const backend = createTestBackend({ config: { dailyDeviceQuota: 5 } });
  const device = await backend.registerDevice();
  await backend.seedClip(); // an available clip does not stop regenerate
  const regenerate = (body) => post(backend, '/v1/clips/regenerate', device.headers, { ...selection, ...body });

  const first = await regenerate({ eventTypes: ['arrival'], count: 3 });
  assert.equal(first.statusCode, 202);
  assert.deepEqual(first.json(), { queued: 3, quotaRemaining: 2 });
  assert.equal(backend.queue.size(), 3);
  const message = await backend.queue.receive(1);
  const job = await backend.store.getJob(message.body.jobId);
  assert.deepEqual(
    [job.eventType, job.language, job.personality, job.humourLevel, job.forceNew, job.requestedBy, job.status],
    ['arrival', 'en-US', 'andy', 2, true, device.deviceId, 'pending'],
  );

  // Event types are optional; only what the quota allows is queued.
  const second = await regenerate({ count: 4 });
  assert.deepEqual(second.json(), { queued: 2, quotaRemaining: 0 });

  const third = await regenerate({ count: 1 });
  assert.equal(third.statusCode, 429);
  assert.equal(errorCode(third), 'quota_exceeded');
  assert.equal(third.json().error.retryAfterSeconds, 14 * 3600 - 60, 'seconds until 00:00 UTC');
  assert.equal(backend.queue.size(), 5);

  for (const body of [{ count: 0 }, { count: 11 }, {}, { count: 1, eventTypes: ['nope'] }]) {
    assert.equal((await regenerate(body)).statusCode, 400, JSON.stringify(body));
  }
});

test('regenerate: 503 budget_exceeded when the budget is spent, 503 generation_unavailable when the queue is down', async () => {
  const broke = createTestBackend({ config: { dailyBudgetUsd: 0 } });
  const device = await broke.registerDevice();
  const refused = await post(broke, '/v1/clips/regenerate', device.headers, { ...selection, count: 1 });
  assert.equal(refused.statusCode, 503);
  assert.equal(errorCode(refused), 'budget_exceeded');

  const backend = createTestBackend();
  const phone = await backend.registerDevice();
  backend.queue.send = async () => {
    throw new Error('queue offline');
  };
  const down = await post(backend, '/v1/clips/regenerate', phone.headers, { ...selection, count: 2 });
  assert.equal(down.statusCode, 503);
  assert.equal(errorCode(down), 'generation_unavailable');
});

test('starter pack: a few oldest clips per event, true totals, empty when the library is empty', async () => {
  const backend = createTestBackend({ config: { starterPerEvent: 2 } });
  const device = await backend.registerDevice();
  const url = '/v1/packs/starter?language=en-US&personality=andy&humourLevel=2';
  assert.deepEqual((await get(backend, url, device.headers)).json(), { clipCount: 0, totalBytes: 0, clips: [] });

  const starts = [await backend.seedClip(), await backend.seedClip(), await backend.seedClip()];
  const arrival = await backend.seedClip({ eventType: 'arrival' });
  await backend.seedClip({ humourLevel: 1 });

  const pack = (await get(backend, url, device.headers)).json();
  assert.deepEqual(pack.clips.map((c) => c.id), [starts[0].id, starts[1].id, arrival.id]);
  assert.equal(pack.clipCount, 3);
  assert.equal(pack.totalBytes, starts[0].audio.length + starts[1].audio.length + arrival.audio.length);

  // Stable: asking again, or having been served the clips, does not change the pack.
  await next(backend, device);
  assert.deepEqual((await get(backend, url, device.headers)).json(), pack);

  assert.equal((await get(backend, '/v1/packs/starter?language=en-US&personality=andy', device.headers)).statusCode, 400);
  assert.equal((await get(backend, '/v1/packs/starter?language=en-US&personality=noura&humourLevel=2', device.headers)).statusCode, 400);
});

test('history: upserts plays, ignores unknown clips, caps a call at 200', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const [a, b] = [await backend.seedClip(), await backend.seedClip()];
  const history = (plays) => post(backend, '/v1/history', device.headers, { plays });
  const at = '2026-11-20T09:00:00Z';

  const first = await history([
    { clipId: a.id, playedAt: at }, { clipId: a.id, playedAt: '2026-11-20T09:30:00Z' }, { clipId: b.id, playedAt: at },
    { clipId: randomUUID(), playedAt: at }, { clipId: 'not-a-uuid', playedAt: at }, { clipId: b.id, playedAt: 'yesterday-ish' },
  ]);
  assert.equal(first.statusCode, 200);
  assert.deepEqual(first.json(), { accepted: 2 });
  assert.deepEqual((await history([{ clipId: a.id, playedAt: at }])).json(), { accepted: 1 }, 'sending a play again is harmless');
  assert.deepEqual((await next(backend, device)).json().clips, []);

  const many = Array.from({ length: 200 }, () => ({ clipId: randomUUID(), playedAt: at }));
  const late = await backend.seedClip();
  assert.deepEqual((await history([...many, { clipId: late.id, playedAt: at }])).json(), { accepted: 0 }, 'entry 201 is not processed');

  assert.equal((await history('nope')).statusCode, 400);
  assert.equal((await history([{ clipId: a.id }])).statusCode, 400);
});

test('audio: full download with length and range support', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const clip = await backend.seedClip();
  const url = `/v1/clips/${clip.id}/audio`;
  const size = clip.audio.length;

  const full = await get(backend, url, device.headers);
  assert.equal(full.statusCode, 200);
  assert.equal(full.headers['content-type'], 'audio/mpeg');
  assert.equal(full.headers['content-length'], String(size));
  assert.equal(full.headers['accept-ranges'], 'bytes');
  assert.ok(full.rawPayload.equals(clip.audio));
});

test('audio: Range gives 206 with Content-Range, so an interrupted download can resume', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const clip = await backend.seedClip();
  const url = `/v1/clips/${clip.id}/audio`;
  const size = clip.audio.length;
  const ranged = (range) => get(backend, url, { ...device.headers, range });

  const resume = await ranged('bytes=10-');
  assert.equal(resume.statusCode, 206);
  assert.equal(resume.headers['content-range'], `bytes 10-${size - 1}/${size}`);
  assert.equal(resume.headers['content-length'], String(size - 10));
  assert.equal(resume.headers['accept-ranges'], 'bytes');
  assert.ok(resume.rawPayload.equals(clip.audio.subarray(10)));
  // First part + resumed part = the whole file.
  const head = await ranged('bytes=0-9');
  assert.equal(head.headers['content-range'], `bytes 0-9/${size}`);
  assert.ok(Buffer.concat([head.rawPayload, resume.rawPayload]).equals(clip.audio));

  const tail = await ranged('bytes=-4');
  assert.equal(tail.statusCode, 206);
  assert.ok(tail.rawPayload.equals(clip.audio.subarray(size - 4)));

  const beyond = await ranged(`bytes=${size}-`);
  assert.equal(beyond.statusCode, 416);
  assert.equal(beyond.headers['content-range'], `bytes */${size}`);
  assert.equal((await ranged('lines=1-2')).statusCode, 200, 'an unknown range unit is ignored');
});

test('audio: 404 for unknown clips and missing blobs, 400 for a bad id, 401 without a token', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const clip = await backend.seedClip();
  const unknown = await get(backend, `/v1/clips/${randomUUID()}/audio`, device.headers);
  assert.equal(unknown.statusCode, 404);
  assert.equal(errorCode(unknown), 'not_found');
  assert.equal((await get(backend, '/v1/clips/not-a-uuid/audio', device.headers)).statusCode, 400);
  assert.equal((await get(backend, `/v1/clips/${clip.id}/audio`)).statusCode, 401);
  backend.blobs.blobs.clear();
  assert.equal((await get(backend, `/v1/clips/${clip.id}/audio`, device.headers)).statusCode, 404);
  assert.equal((await get(backend, '/v1/nothing-here', device.headers)).statusCode, 404);
});

test('me/delete: erases the device, its token stops working, and the same install starts again with no history', async () => {
  const backend = createTestBackend({ config: { dailyDeviceQuota: 5 } });
  const installId = 'install-to-be-forgotten';
  const device = await backend.registerDevice(installId);
  const bystander = await backend.registerDevice();
  const clip = await backend.seedClip();

  // Build up some history: served, played, quota used, a report, a pending job.
  assert.deepEqual((await next(backend, device, { want: 2 })).json().clips.map((c) => c.id), [clip.id]);
  await post(backend, '/v1/history', device.headers, { plays: [{ clipId: clip.id, playedAt: backend.now().toISOString() }] });
  await post(backend, '/v1/pronunciation/reports', device.headers, { text: 'Lusail', language: 'en-US', note: 'from the deleted device' });
  await post(backend, '/v1/pronunciation/reports', bystander.headers, { text: 'Katara', language: 'en-US', note: 'from someone else' });
  await next(backend, bystander, { want: 1 });
  assert.deepEqual((await next(backend, device)).json().clips, []);
  assert.equal(await backend.store.getQuotaUsed(device.deviceId, '2026-11-20'), 1);

  assert.equal((await post(backend, '/v1/me/delete', {})).statusCode, 401);
  const deleted = await post(backend, '/v1/me/delete', device.headers);
  assert.equal(deleted.statusCode, 200);
  assert.deepEqual(deleted.json(), { deleted: true });

  // The old token is rejected everywhere, including for a second delete.
  for (const [method, url] of [['GET', '/v1/voices?language=en-US'], ['POST', '/v1/clips/next'], ['POST', '/v1/me/delete'], ['GET', `/v1/clips/${clip.id}/audio`]]) {
    const response = await backend.app.inject({ method, url, headers: device.headers });
    assert.equal(response.statusCode, 401, `${method} ${url}`);
  }
  const reports = (await get(backend, '/v1/admin/pronunciation/reports', admin)).json().reports;
  assert.deepEqual(reports.map((r) => r.note), ['from someone else']);
  assert.equal(await backend.store.getQuotaUsed(device.deviceId, '2026-11-20'), 0);

  // Same install id: a new device, a new token, and the clip is offered again.
  const reborn = await backend.registerDevice(installId);
  assert.notEqual(reborn.deviceId, device.deviceId);
  assert.notEqual(reborn.token, device.token);
  const fresh = (await next(backend, reborn, { want: 1 })).json();
  assert.deepEqual([fresh.clips.map((c) => c.id), fresh.quotaRemaining], [[clip.id], 5]);

  // Nobody else lost anything.
  assert.deepEqual((await next(backend, bystander, { want: 1 })).json().clips, [], 'the bystander still has its history');
});

test('pronunciation reports: stored for the admin to read', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const clip = await backend.seedClip();
  const report = (body) => post(backend, '/v1/pronunciation/reports', device.headers, body);

  const withClip = await report({ clipId: clip.id, text: 'الدوحة', language: 'ar-QA', note: 'stress is on the wrong syllable' });
  assert.equal(withClip.statusCode, 201);
  assert.match(withClip.json().id, /^[0-9a-f-]{36}$/);
  backend.clock.ms += 1000;
  const withoutClip = await report({ text: 'Lusail', language: 'en-US', note: '' });
  assert.equal(withoutClip.statusCode, 201);
  backend.clock.ms += 1000;
  const unknownClip = await report({ clipId: randomUUID(), text: 'Katara', language: 'en-US', note: 'n' });
  assert.equal(unknownClip.statusCode, 201);
  assert.equal((await report({ language: 'en-US', note: 'no text' })).statusCode, 400);
  assert.equal((await report({ clipId: 'x', text: 't', language: 'en-US', note: 'n' })).statusCode, 400);

  const listed = (await get(backend, '/v1/admin/pronunciation/reports?status=open', admin)).json().reports;
  assert.deepEqual(listed.map((r) => [r.text, r.clipId, r.deviceId, r.status]), [
    ['Katara', null, device.deviceId, 'open'], ['Lusail', null, device.deviceId, 'open'], ['الدوحة', clip.id, device.deviceId, 'open'],
  ]);
  assert.equal(listed[2].note, 'stress is on the wrong syllable');
  assert.deepEqual((await get(backend, '/v1/admin/pronunciation/reports?status=closed', admin)).json(), { reports: [] });
  assert.equal((await get(backend, '/v1/admin/pronunciation/reports', admin)).json().reports.length, 3);
});

test('admin routes need the admin key; a device token or app key is not enough', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const routes = [
    ['POST', '/v1/admin/pregenerate', { ...selection, perEvent: 1 }],
    ['GET', '/v1/admin/usage'],
    ['GET', '/v1/admin/pronunciation'],
    ['PUT', '/v1/admin/pronunciation', { language: 'en', term: 'Doha', spoken: 'DOE-ha', reviewed: true }],
    ['GET', '/v1/admin/pronunciation/reports'],
  ];
  for (const [method, url, payload] of routes) {
    for (const headers of [{}, device.headers, { 'x-admin-key': 'wrong' }, { 'x-admin-key': APP_KEY }, { 'x-app-key': APP_KEY }]) {
      const response = await backend.app.inject({ method, url, headers, payload });
      assert.equal(response.statusCode, 401, `${method} ${url} ${JSON.stringify(Object.keys(headers))}`);
      assert.equal(errorCode(response), 'unauthorized');
    }
    const allowed = await backend.app.inject({ method, url, headers: admin, payload });
    assert.ok([200, 202].includes(allowed.statusCode), `${method} ${url} -> ${allowed.statusCode}`);
  }
  assert.equal(backend.queue.size(), 14, 'only the authorised pregenerate queued anything');
});

test('admin pregenerate queues perEvent jobs for every event; the worker fills the library', async () => {
  const backend = createTestBackend();
  const pregenerate = (body) => post(backend, '/v1/admin/pregenerate', admin, { ...selection, ...body });
  const response = await pregenerate({ perEvent: 2 });
  assert.equal(response.statusCode, 202);
  assert.deepEqual(response.json(), { queued: 28 });

  backend.textGenerator.push(...Array.from({ length: 28 }, (_, i) => `${'abcdefghijklmnopqrstuvwxyzAB'[i]}${i} starts line ${i * 7919}, then ends ${i}.`));
  while (await backend.worker.runOnce());
  const device = await backend.registerDevice();
  const pack = (await get(backend, '/v1/packs/starter?language=en-US&personality=andy&humourLevel=2', device.headers)).json();
  assert.equal(pack.clipCount, 28);
  assert.deepEqual([...new Set(pack.clips.map((c) => c.eventType))], EVENT_TYPES);

  assert.equal((await pregenerate({ perEvent: 21 })).statusCode, 400);
  assert.equal((await pregenerate({ perEvent: 1, language: 'ar-JO', personality: 'abu-fahad' })).statusCode, 400, 'needs a language with its own voice');
  const broke = createTestBackend({ config: { dailyBudgetUsd: 0 } });
  assert.equal(errorCode(await post(broke, '/v1/admin/pregenerate', admin, { ...selection, perEvent: 1 })), 'budget_exceeded');
});

test('admin usage: per-day counters, estimated cost and the budget', async () => {
  const backend = createTestBackend({ config: { dailyBudgetUsd: 7.5, priceGrokPerCallUsd: 0.01, priceTtsPerMillionCharsUsd: 20 } });
  await backend.store.addUsage('2026-11-19', { grokCalls: 10, ttsCharacters: 500_000, bytesStored: 1_000_000 });
  backend.textGenerator.push(JOKES[0]);
  const clip = await backend.generator.generate({ eventType: 'arrival', ...selection, requestedBy: null, forceNew: true });

  const response = await get(backend, '/v1/admin/usage?days=3', admin);
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), {
    dailyBudgetUsd: 7.5,
    days: [
      { date: '2026-11-20', grokCalls: 1, ttsCharacters: JOKES[0].length, bytesStored: clip.bytes, estimatedCostUsd: 0.01094 },
      { date: '2026-11-19', grokCalls: 10, ttsCharacters: 500_000, bytesStored: 1_000_000, estimatedCostUsd: 10.1 },
      { date: '2026-11-18', grokCalls: 0, ttsCharacters: 0, bytesStored: 0, estimatedCostUsd: 0 },
    ],
  });
  assert.equal((await get(backend, '/v1/admin/usage', admin)).json().days.length, 7);
  assert.equal((await get(backend, '/v1/admin/usage?days=0', admin)).statusCode, 400);
});

test('admin pronunciation: upsert by normalised term, list by language, applied only once reviewed', async () => {
  const backend = createTestBackend();
  const put = (body) => backend.app.inject({ method: 'PUT', url: '/v1/admin/pronunciation', headers: admin, payload: body });

  const created = await put({ language: 'en-US', term: 'Snacks', spoken: 'SNAX', reviewed: false });
  assert.equal(created.statusCode, 200);
  assert.deepEqual(
    { ...created.json(), updatedAt: undefined },
    { id: created.json().id, language: 'en-US', term: 'Snacks', spoken: 'SNAX', ipa: null, reviewed: false, updatedAt: undefined },
  );
  await put({ language: 'ar-QA', term: 'الدَّوْحَة', ipa: 'adˈdoːħa', reviewed: true });
  // Same term with other tashkeel and spelling: the same entry, updated.
  const updated = await put({ language: 'ar-QA', term: 'الدوحه', spoken: 'الدَّوْحَة', reviewed: true });
  assert.deepEqual([updated.json().spoken, updated.json().ipa], ['الدَّوْحَة', null]);

  const all = (await get(backend, '/v1/admin/pronunciation', admin)).json().entries;
  assert.deepEqual(all.map((e) => [e.language, e.term]), [['en-US', 'Snacks'], ['ar-QA', 'الدوحه']]);
  assert.equal((await get(backend, '/v1/admin/pronunciation?language=ar-QA', admin)).json().entries.length, 1);

  // Unreviewed: not applied. Reviewed: applied to the next clip.
  const generate = () => backend.generator.generate({ eventType: 'arrival', ...selection, requestedBy: null, forceNew: true });
  backend.textGenerator.push(JOKES[2], 'Whoever packed the snacks deserves a medal and a nap.');
  await generate();
  assert.ok(!backend.speech.ssml[0].includes('<sub'));
  await put({ language: 'en-US', term: 'snacks', spoken: 'SNAX', reviewed: true });
  await generate();
  assert.ok(backend.speech.ssml[1].includes('<sub alias="SNAX">snacks</sub>'));

  for (const body of [{ language: 'en', term: 'x', reviewed: true }, { language: 'en', spoken: 'x', reviewed: true }, { language: 'en', term: '!!!', spoken: 'x', reviewed: true }]) {
    assert.equal((await put(body)).statusCode, 400, JSON.stringify(body));
  }
});

test('request logs never contain tokens or keys', async () => {
  const lines = [];
  const { default: pino } = await import('pino');
  const { buildApp } = await import('../src/app.js');
  const backend = createTestBackend();
  const logger = pino({ level: 'debug' }, { write: (line) => lines.push(line) });
  const app = buildApp({ ...backend, logger });
  const device = await backend.registerDevice();

  await app.inject({ method: 'POST', url: '/v1/devices/register', headers: { 'x-app-key': APP_KEY }, payload: { installId: 'install-secret-id' } });
  await app.inject({ method: 'GET', url: '/v1/voices?language=en-US', headers: device.headers });
  await app.inject({ method: 'GET', url: '/v1/admin/usage', headers: admin });
  await app.inject({ method: 'GET', url: '/v1/admin/usage', headers: { 'x-admin-key': 'wrong-admin-key' } });

  const log = lines.join('');
  assert.ok(lines.length >= 8 && log.includes('/v1/voices?language=en-US'), 'requests are logged');
  for (const secret of [device.token, APP_KEY, ADMIN_KEY, 'wrong-admin-key', 'install-secret-id', backend.config.tokenSecret]) {
    assert.ok(!log.includes(secret), `log contains ${secret}`);
  }
});
