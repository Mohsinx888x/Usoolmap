// One contract suite, run against the in-memory store and, when TEST_DATABASE_URL is set,
// against a real PostgreSQL server. The PostgreSQL run works in a schema of its own that is
// created for the run and dropped afterwards, so the database it points at is left untouched.

import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { after, before, describe, test } from 'node:test';
import pg from 'pg';
import { normalize, openingPhrase } from '../src/similarity.js';
import { createMemoryStore } from '../src/store/memory.js';
import { createPostgresStore } from '../src/store/postgres.js';
import { ADMIN_KEY, JOKES, createTestBackend } from './helpers.js';

const DATABASE_URL = process.env.TEST_DATABASE_URL;
const T0 = Date.parse('2026-11-20T10:00:00Z');
const at = (minutes) => new Date(T0 + minutes * 60_000);
const uniqueLanguage = () => `zz-${randomUUID().slice(0, 8)}`;
const uniqueDay = () => `${2100 + Math.floor(Math.random() * 7000)}-0${1 + Math.floor(Math.random() * 9)}-1${Math.floor(Math.random() * 9)}`;

function clipFields(language, overrides = {}) {
  const id = randomUUID();
  const text = overrides.text ?? `Line ${id}`;
  return {
    id, eventType: 'route_start', language, personality: 'andy', humourLevel: 2, text, normalizedText: normalize(text),
    opening: openingPhrase(text), voice: 'en-US-AndrewMultilingualNeural', fallbackVoice: false,
    blobPath: `clips/${language}/route_start/${id}.mp3`, bytes: 1234, status: 'ready', source: 'generated', createdAt: at(0), ...overrides,
  };
}

function storeContract(name, createStore) {
  describe(`Store contract: ${name}`, () => {
    let store;
    before(async () => {
      store = createStore();
      await store.init();
    });
    after(() => store.close());

    const addClip = async (language, overrides) => {
      const clip = clipFields(language, overrides);
      await store.insertClip(clip);
      return clip;
    };
    const addDevice = () => store.upsertDevice(randomUUID(), `hash-${randomUUID()}`);

    test('init can run again (the schema is idempotent)', async () => {
      await store.init();
    });

    test('devices: the same install hash always gives the same device', async () => {
      const hash = `hash-${randomUUID()}`;
      const id = await store.upsertDevice(randomUUID(), hash);
      assert.equal(await store.upsertDevice(randomUUID(), hash), id);
      assert.notEqual(await addDevice(), id);
      assert.equal(await store.deviceExists(id), true);
      assert.equal(await store.deviceExists(randomUUID()), false);
    });

    test('clips: insert, get, recent texts newest first, exact-text lookup', async () => {
      const language = uniqueLanguage();
      const first = await addClip(language, { text: 'First line here', createdAt: at(1) });
      const second = await addClip(language, { text: 'Second line there', createdAt: at(2), personality: 'ava' });
      await addClip(language, { eventType: 'arrival', createdAt: at(3) });

      assert.deepEqual(await store.getClip(first.id), first);
      assert.equal(await store.getClip(randomUUID()), null);
      assert.deepEqual(await store.recentClips('route_start', language, 10), [
        { text: second.text, normalizedText: 'second line there', opening: 'second line' },
        { text: first.text, normalizedText: 'first line here', opening: 'first line' },
      ]);
      assert.equal((await store.recentClips('route_start', language, 1)).length, 1);
      assert.equal(await store.hasClipText('route_start', language, 'first line here'), true);
      assert.equal(await store.hasClipText('arrival', language, 'first line here'), false);
      assert.equal(await store.hasClipText('route_start', uniqueLanguage(), 'first line here'), false);
    });

    test('clips: the same normalised text cannot be stored twice for one language and event', async () => {
      const language = uniqueLanguage();
      await addClip(language, { text: 'Same line' });
      await assert.rejects(addClip(language, { text: 'Same line' }));
      await addClip(language, { text: 'Same line', eventType: 'arrival' });
    });

    test('findClips: filters, per-event limit, oldest first, requested event order, excludeIds', async () => {
      const language = uniqueLanguage();
      const wanted = { language, personality: 'andy', humourLevel: 2 };
      const s1 = await addClip(language, { createdAt: at(1) });
      const s2 = await addClip(language, { createdAt: at(2) });
      const s3 = await addClip(language, { createdAt: at(3) });
      const a1 = await addClip(language, { eventType: 'arrival', createdAt: at(0) });
      await addClip(language, { personality: 'ava' });
      await addClip(language, { humourLevel: 1 });
      await addClip(language, { status: 'pending' });
      await addClip(language, { eventType: 'reroute' });

      const ids = async (query) => (await store.findClips({ ...wanted, ...query })).map((c) => c.id);
      assert.deepEqual(await ids({ eventTypes: ['route_start', 'arrival'], perEvent: 2 }), [s1.id, s2.id, a1.id]);
      assert.deepEqual(await ids({ eventTypes: ['arrival', 'route_start'], perEvent: 5 }), [a1.id, s1.id, s2.id, s3.id]);
      assert.deepEqual(await ids({ eventTypes: ['route_start'], perEvent: 2, excludeIds: [s1.id] }), [s2.id, s3.id]);
      assert.deepEqual(await ids({ eventTypes: ['u_turn'], perEvent: 2 }), []);
      const [found] = await store.findClips({ ...wanted, eventTypes: ['arrival'], perEvent: 1 });
      assert.deepEqual(found, a1);
    });

    test('plays: served or played clips are hidden from that device inside the window only', async () => {
      const language = uniqueLanguage();
      const [device, other] = [await addDevice(), await addDevice()];
      const [a, b, c] = [await addClip(language, { createdAt: at(1) }), await addClip(language, { createdAt: at(2) }), await addClip(language, { createdAt: at(3) })];
      const unseen = async (deviceId, since) =>
        (await store.findClips({ language, personality: 'andy', humourLevel: 2, eventTypes: ['route_start'], perEvent: 9, unseenBy: { deviceId, since } })).map((x) => x.id);

      assert.equal(await store.recordPlays(device, 'served', [{ clipId: a.id, at: at(10) }]), 1);
      assert.equal(await store.recordPlays(device, 'played', [{ clipId: b.id, at: at(20) }]), 1);
      assert.deepEqual(await unseen(device, at(0)), [c.id]);
      assert.deepEqual(await unseen(other, at(0)), [a.id, b.id, c.id]);
      assert.deepEqual(await unseen(device, at(15)), [a.id, c.id], 'a was served before the window started');
      assert.deepEqual(await unseen(device, at(25)), [a.id, b.id, c.id]);
    });

    test('plays: upsert keeps the latest time, unknown clips are ignored, duplicates in a batch are fine', async () => {
      const language = uniqueLanguage();
      const device = await addDevice();
      const clip = await addClip(language);
      const unseen = async (since) =>
        (await store.findClips({ language, personality: 'andy', humourLevel: 2, eventTypes: ['route_start'], perEvent: 9, unseenBy: { deviceId: device, since } })).length;

      const accepted = await store.recordPlays(device, 'played', [
        { clipId: clip.id, at: at(30) }, { clipId: clip.id, at: at(10) }, { clipId: randomUUID(), at: at(10) },
      ]);
      assert.equal(accepted, 1);
      assert.equal(await store.recordPlays(device, 'played', [{ clipId: clip.id, at: at(5) }]), 1);
      assert.equal(await unseen(at(25)), 0, 'an older play does not move the time back');
      assert.equal(await store.recordPlays(device, 'played', []), 0);
      assert.equal(await store.recordPlays(device, 'played', [{ clipId: randomUUID(), at: at(1) }]), 0);
    });

    test('deleteDevice removes the device, its plays, quota and reports, and detaches its jobs; other devices are untouched', async () => {
      const language = uniqueLanguage();
      const day = uniqueDay();
      const marker = `note-${randomUUID()}`;
      const hash = `hash-${randomUUID()}`;
      const device = await store.upsertDevice(randomUUID(), hash);
      const other = await addDevice();
      const clip = await addClip(language);
      const job = { id: randomUUID(), eventType: 'arrival', language, personality: 'andy', humourLevel: 2, forceNew: false, status: 'pending', createdAt: at(1) };
      const unseen = async (deviceId) =>
        (await store.findClips({ language, personality: 'andy', humourLevel: 2, eventTypes: ['route_start'], perEvent: 9, unseenBy: { deviceId, since: at(0) } })).length;

      for (const id of [device, other]) {
        await store.recordPlays(id, 'served', [{ clipId: clip.id, at: at(5) }]);
        await store.recordPlays(id, 'played', [{ clipId: clip.id, at: at(6) }]);
        await store.consumeQuota(id, day, 3, 5);
        await store.insertReport({ id: randomUUID(), deviceId: id, clipId: clip.id, text: 't', language, note: marker, createdAt: at(1) });
      }
      await store.insertJob({ ...job, requestedBy: device });
      const otherJob = { ...job, id: randomUUID(), requestedBy: other };
      await store.insertJob(otherJob);

      await store.deleteDevice(device);

      assert.equal(await store.deviceExists(device), false);
      assert.equal(await unseen(device), 1, 'no play history is left');
      assert.equal(await store.getQuotaUsed(device, day), 0);
      const reports = (await store.listReports()).filter((r) => r.note === marker);
      assert.deepEqual(reports.map((r) => r.deviceId), [other]);
      assert.equal((await store.getJob(job.id)).requestedBy, null);
      assert.equal((await store.getJob(job.id)).status, 'pending', 'the job itself is kept');

      assert.equal(await store.deviceExists(other), true);
      assert.equal(await unseen(other), 0);
      assert.equal(await store.getQuotaUsed(other, day), 3);
      assert.equal((await store.getJob(otherJob.id)).requestedBy, other);
      assert.ok(await store.getClip(clip.id), 'shared clips are not touched');

      // The same install registers again as a brand-new device.
      const again = await store.upsertDevice(randomUUID(), hash);
      assert.notEqual(again, device);
      await store.deleteDevice(randomUUID()); // unknown device: nothing happens
    });

    test('jobs: insert, get, update, pending lookup by combination and age', async () => {
      const language = uniqueLanguage();
      const key = { eventType: 'reroute', language, personality: 'andy', humourLevel: 3 };
      const job = { id: randomUUID(), ...key, requestedBy: null, forceNew: true, status: 'pending', createdAt: at(10) };
      assert.equal(await store.hasPendingJob(key, at(0)), false);
      await store.insertJob(job);

      assert.deepEqual(await store.getJob(job.id), { ...job, attempts: 0, error: null, finishedAt: null });
      assert.equal(await store.getJob(randomUUID()), null);
      assert.equal(await store.hasPendingJob(key, at(0)), true);
      assert.equal(await store.hasPendingJob(key, at(11)), false, 'too old to count');
      assert.equal(await store.hasPendingJob({ ...key, humourLevel: 2 }, at(0)), false);
      assert.equal(await store.hasPendingJob({ ...key, eventType: 'arrival' }, at(0)), false);

      await store.updateJob(job.id, { attempts: 2, error: 'error: boom' });
      assert.deepEqual(await store.getJob(job.id), { ...job, attempts: 2, error: 'error: boom', finishedAt: null });
      await store.updateJob(job.id, { status: 'done', error: null, finishedAt: at(12) });
      assert.deepEqual(await store.getJob(job.id), { ...job, status: 'done', attempts: 2, error: null, finishedAt: at(12) });
      assert.equal(await store.hasPendingJob(key, at(0)), false);

      const device = await addDevice();
      const requested = { id: randomUUID(), ...key, requestedBy: device, forceNew: false, status: 'pending', createdAt: at(1) };
      await store.insertJob(requested);
      assert.equal((await store.getJob(requested.id)).requestedBy, device);
    });

    test('quota: grants up to the limit per device and day, also under concurrency', async () => {
      const [device, other] = [randomUUID(), randomUUID()];
      const day = uniqueDay();
      assert.equal(await store.getQuotaUsed(device, day), 0);
      assert.equal(await store.consumeQuota(device, day, 3, 5), 3);
      assert.equal(await store.consumeQuota(device, day, 3, 5), 2);
      assert.equal(await store.consumeQuota(device, day, 1, 5), 0);
      assert.equal(await store.getQuotaUsed(device, day), 5);
      assert.equal(await store.consumeQuota(other, day, 1, 5), 1);
      assert.equal(await store.consumeQuota(device, uniqueDay(), 9, 5), 5);

      const racer = randomUUID();
      const grants = await Promise.all(Array.from({ length: 12 }, () => store.consumeQuota(racer, day, 1, 5)));
      assert.equal(grants.reduce((sum, granted) => sum + granted, 0), 5);
      assert.equal(await store.getQuotaUsed(racer, day), 5);
    });

    test('usage: counters add up per day and are listed newest first from a given day', async () => {
      const year = 2100 + Math.floor(Math.random() * 7000);
      const [d1, d2, d3] = [`${year}-03-01`, `${year}-03-02`, `${year}-03-03`];
      await store.addUsage(d1, { grokCalls: 1, ttsCharacters: 10, bytesStored: 100 });
      await store.addUsage(d2, { grokCalls: 2, ttsCharacters: 20, bytesStored: 200 });
      await store.addUsage(d2, { grokCalls: 3, ttsCharacters: 3_000_000_000, bytesStored: 5_000_000_000 });
      await store.addUsage(d3, { grokCalls: 0, ttsCharacters: 0, bytesStored: 7 });
      const rows = (await store.getUsage(d2)).filter((row) => row.day.startsWith(`${year}-`));
      assert.deepEqual(rows, [
        { day: d3, grokCalls: 0, ttsCharacters: 0, bytesStored: 7 },
        { day: d2, grokCalls: 5, ttsCharacters: 3_000_000_020, bytesStored: 5_000_000_200 },
      ]);
    });

    test('pronunciation: upsert on language + normalised term, filters by language and review state', async () => {
      const language = uniqueLanguage();
      const base = { language, term: 'الدوحة', normalizedTerm: normalize('الدوحة'), spoken: 'الدَّوْحَة', ipa: null, reviewed: false, updatedAt: at(1) };
      const created = await store.upsertPronunciation(base);
      assert.deepEqual(created, { id: created.id, ...base });
      assert.equal(typeof created.id, 'number');

      const updated = await store.upsertPronunciation({ ...base, term: 'الدَّوحة', spoken: null, ipa: 'adˈdoːħa', reviewed: true, updatedAt: at(2) });
      assert.deepEqual(updated, { id: created.id, ...base, term: 'الدَّوحة', spoken: null, ipa: 'adˈdoːħa', reviewed: true, updatedAt: at(2) });
      const draft = await store.upsertPronunciation({ ...base, term: 'لوسيل', normalizedTerm: 'لوسيل', spoken: 'لُوسَيْل' });
      const otherLanguage = uniqueLanguage();
      await store.upsertPronunciation({ ...base, language: otherLanguage, reviewed: true });

      assert.deepEqual((await store.listPronunciation([language])).map((e) => e.id), [created.id, draft.id]);
      assert.deepEqual((await store.listPronunciation([language], true)).map((e) => e.term), ['الدَّوحة']);
      assert.equal((await store.listPronunciation([language, otherLanguage], true)).length, 2);
      assert.deepEqual(await store.listPronunciation([uniqueLanguage()]), []);
      assert.ok((await store.listPronunciation()).length >= 3, 'no language filter lists everything');
    });

    test('reports: stored open, listed newest first, filtered by status', async () => {
      const marker = `note-${randomUUID()}`;
      const device = randomUUID();
      const clip = await addClip(uniqueLanguage());
      const first = { id: randomUUID(), deviceId: device, clipId: clip.id, text: 'الدوحة', language: 'ar-QA', note: marker, createdAt: at(1) };
      const second = { id: randomUUID(), deviceId: device, clipId: null, text: 'Lusail', language: 'en-US', note: marker, createdAt: at(2) };
      await store.insertReport(first);
      await store.insertReport(second);
      const mine = async (status) => (await store.listReports(status)).filter((r) => r.note === marker);
      assert.deepEqual(await mine('open'), [{ ...second, status: 'open' }, { ...first, status: 'open' }]);
      assert.equal((await mine()).length, 2);
      assert.deepEqual(await mine('closed'), []);
    });

    test('whole backend over this store: pregenerate, worker, next, history, usage', async () => {
      const backend = createTestBackend({ store });
      const language = { language: 'en-GB', personality: 'sonia', humourLevel: 3 };
      // The pipeline dedupes against everything already in this database, so each run needs new jokes.
      const run = randomUUID().replaceAll('-', ' ');
      const day = '2026-11-20';
      const usageOfDay = async () => (await store.getUsage(day)).find((row) => row.day === day) ?? { grokCalls: 0, ttsCharacters: 0 };
      const earlier = await usageOfDay();

      const queued = await backend.app.inject({ method: 'POST', url: '/v1/admin/pregenerate', headers: { 'x-admin-key': ADMIN_KEY }, payload: { ...language, perEvent: 1 } });
      assert.deepEqual(queued.json(), { queued: 14 });
      const lines = Array.from({ length: 14 }, (_, i) => `${run.split(' ')[i % 5]}${i} ${JOKES[i % JOKES.length]} ${run}`);
      backend.textGenerator.push(...lines);
      while (await backend.worker.runOnce());

      const device = await backend.registerDevice();
      const request = () => backend.app.inject({
        method: 'POST', url: '/v1/clips/next', headers: device.headers, payload: { eventTypes: ['arrival', 'u_turn'], ...language, want: 20 },
      });
      const served = (await request()).json().clips.filter((clip) => lines.includes(clip.text));
      assert.equal(served.length, 2);
      assert.equal(served[0].voice, 'en-GB-SoniaNeural');
      assert.ok((await request()).json().clips.every((clip) => !served.some((s) => s.id === clip.id)), 'no repeats');

      const audio = await backend.app.inject({ method: 'GET', url: served[0].audioUrl, headers: { ...device.headers, range: 'bytes=5-' } });
      assert.equal(audio.statusCode, 206);
      assert.equal(audio.rawPayload.length, served[0].bytes - 5);

      const history = await backend.app.inject({
        method: 'POST', url: '/v1/history', headers: device.headers, payload: { plays: [{ clipId: served[0].id, playedAt: '2026-11-20T10:00:00Z' }] },
      });
      assert.deepEqual(history.json(), { accepted: 1 });

      const today = await usageOfDay();
      assert.equal(today.grokCalls - earlier.grokCalls, 14);
      assert.equal(today.ttsCharacters - earlier.ttsCharacters, lines.join('').length);
    });
  });
}

storeContract('memory', createMemoryStore);

if (DATABASE_URL) {
  describe('PostgreSQL', () => {
    const schema = `test_${randomUUID().replaceAll('-', '')}`;
    const url = new URL(DATABASE_URL);
    url.searchParams.set('options', `-c search_path=${schema}`);
    const admin = new pg.Pool({ connectionString: DATABASE_URL });
    const pool = new pg.Pool({ connectionString: url.href });

    before(() => admin.query(`create schema ${schema}`));
    after(async () => {
      await pool.end();
      await admin.query(`drop schema ${schema} cascade`);
      await admin.end();
    });

    storeContract('PostgreSQL', () => createPostgresStore(url.href));

    test('schema: all tables and the clips lookup index exist', async () => {
      const tables = await pool.query('select table_name from information_schema.tables where table_schema = $1', [schema]);
      const names = tables.rows.map((row) => row.table_name);
      for (const table of ['devices', 'clips', 'plays', 'jobs', 'usage_daily', 'device_quota', 'pronunciation', 'pronunciation_reports']) {
        assert.ok(names.includes(table), table);
      }
      const index = await pool.query("select indexdef from pg_indexes where schemaname = $1 and indexname = 'clips_lookup_idx'", [schema]);
      assert.match(index.rows[0].indexdef, /\(language, event_type, personality, humour_level, status, created_at\)/);
    });

    test('schema: the Qatar place-name seed is unreviewed, vowelled, keyed as the code would key it, and not duplicated by re-running', async () => {
      const { rows } = await pool.query("select term, normalized_term, spoken, reviewed from pronunciation where language = 'ar-QA' and term = any($1)", [
        ['الدوحة', 'الوكرة', 'الخور', 'لوسيل', 'الريان', 'الكورنيش', 'سوق واقف', 'كتارا', 'مشيرب', 'الدفنة'],
      ]);
      assert.equal(rows.length, 10);
      for (const row of rows) {
        assert.equal(row.normalized_term, normalize(row.term), row.term);
        assert.equal(normalize(row.spoken), row.normalized_term, `${row.term}: the spoken form is the same word`);
        assert.ok(/[ً-ْ]/.test(row.spoken), `${row.term} has tashkeel`);
      }
      assert.ok(rows.every((row) => row.reviewed === false), 'seeded entries start unreviewed');
    });

    test('schema: constraints: plays.kind is checked and plays need a known device and clip', async () => {
      await assert.rejects(pool.query("insert into plays values ($1, $2, 'served', now())", [randomUUID(), randomUUID()]), /foreign key/);
      const device = randomUUID();
      const clip = clipFields(uniqueLanguage());
      await pool.query('insert into devices (id, install_id_hash) values ($1, $2)', [device, `hash-${device}`]);
      await pool.query(
        `insert into clips (id, event_type, language, personality, humour_level, text, normalized_text, opening, voice, blob_path, bytes)
         values ($1, 'arrival', $2, 'andy', 2, 't', 't', 't', 'v', 'p', 1)`,
        [clip.id, clip.language],
      );
      await assert.rejects(pool.query("insert into plays values ($1, $2, 'skipped', now())", [device, clip.id]), /plays_kind_check/);
      await pool.query("insert into plays values ($1, $2, 'played', now())", [device, clip.id]);
    });
  });
} else {
  test('PostgreSQL store contract', { skip: 'set TEST_DATABASE_URL to run the store contract against a real PostgreSQL server' }, () => {});
}
