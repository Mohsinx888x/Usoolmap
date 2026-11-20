import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { runVoiceQa } from '../scripts/voice-qa.js';
import { EVENT_TYPES } from '../src/events.js';
import { GenerationError } from '../src/generator.js';
import { ARABIC_JOKES, JOKES, createTestBackend, fakeSpeech, scriptedTextGenerator } from './helpers.js';

const job = (overrides = {}) => ({
  eventType: 'route_start', language: 'en-US', personality: 'andy', humourLevel: 2, requestedBy: null, forceNew: true, ...overrides,
});
const today = '2026-11-20';
const usageToday = async (backend) => (await backend.store.getUsage(today))[0];

test('generates a clip end to end: text, SSML, audio, blob, row and usage', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(`"${JOKES[0]}"\n`); // wrapped in quotes with a trailing newline, as models do
  const clip = await backend.generator.generate(job());

  assert.equal(clip.text, JOKES[0]);
  assert.deepEqual(
    [clip.eventType, clip.language, clip.personality, clip.humourLevel, clip.voice, clip.status, clip.fallbackVoice],
    ['route_start', 'en-US', 'andy', 2, 'en-US-AndrewMultilingualNeural', 'ready', false],
  );
  assert.equal(clip.blobPath, `clips/en-US/route_start/${clip.id}.mp3`);
  assert.equal(clip.opening, 'buckle up');

  const [document] = backend.speech.ssml;
  assert.ok(document.includes('<voice name="en-US-AndrewMultilingualNeural">Buckle up, the road has no idea what is coming.</voice>'));
  const blob = backend.blobs.blobs.get(clip.blobPath);
  assert.equal(blob.contentType, 'audio/mpeg');
  assert.equal(blob.data.toString(), `FAKE-MP3:${document}`);
  assert.equal(clip.bytes, blob.data.length);

  assert.deepEqual(await backend.store.getClip(clip.id), clip);
  assert.deepEqual(await usageToday(backend), { day: today, grokCalls: 1, ttsCharacters: JOKES[0].length, bytesStored: clip.bytes });
});

test('the prompt carries the recent jokes and openings for that event and language', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0], JOKES[1], JOKES[2]);
  await backend.generator.generate(job());
  await backend.generator.generate(job({ eventType: 'arrival' })); // other event: must not be listed
  await backend.generator.generate(job());
  const { user } = backend.textGenerator.prompts[2];
  assert.ok(user.includes(`- ${JOKES[0]}`) && user.includes('- buckle up'));
  assert.ok(!user.includes(JOKES[1]));
});

test('an identical text is rejected, the model is told why, and the retry is used', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0], 'BUCKLE UP: the road has no idea what is coming!!', JOKES[1]);
  await backend.generator.generate(job());
  const clip = await backend.generator.generate(job({ personality: 'ava' })); // dedupe spans personalities
  assert.equal(clip.text, JOKES[1]);
  assert.equal(backend.textGenerator.prompts.length, 3);
  assert.ok(backend.textGenerator.prompts[2].user.includes('(rejected: identical to an existing line)'));
  assert.equal((await usageToday(backend)).grokCalls, 3, 'the rejected call is still counted');
});

test('a reworded near-copy is rejected by shingle similarity', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(
    'Recalculating, because apparently the scenic route chose us today.',
    'Honestly recalculating, because apparently the scenic route chose us again today.',
    JOKES[2],
  );
  await backend.generator.generate(job({ eventType: 'reroute' }));
  const clip = await backend.generator.generate(job({ eventType: 'reroute' }));
  assert.equal(clip.text, JOKES[2]);
  assert.ok(backend.textGenerator.prompts[2].user.includes('rejected: too similar in wording'));
});

test('a text with a recently used opening is rejected', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0], 'Buckle up, my friend: today the snacks are entirely on me.', JOKES[3]);
  await backend.generator.generate(job());
  const clip = await backend.generator.generate(job());
  assert.equal(clip.text, JOKES[3]);
  assert.ok(backend.textGenerator.prompts[2].user.includes('rejected: starts with "buckle up"'));
});

test('the same text is fine for another event type or language', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0], JOKES[0], JOKES[0]);
  await backend.generator.generate(job());
  await backend.generator.generate(job({ eventType: 'arrival' }));
  await backend.generator.generate(job({ language: 'en-GB', personality: 'ryan' }));
  assert.equal(backend.textGenerator.prompts.length, 3);
});

test('gives up after three attempts without synthesising or storing anything', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0], JOKES[0], JOKES[0], JOKES[0], JOKES[1]);
  await backend.generator.generate(job());
  await assert.rejects(backend.generator.generate(job()), (error) => {
    assert.ok(error instanceof GenerationError);
    assert.equal(error.code, 'no_acceptable_text');
    return true;
  });
  assert.equal(backend.textGenerator.prompts.length, 4, 'one call for the first clip, exactly three for the second');
  assert.equal(backend.speech.ssml.length, 1);
  assert.equal(backend.blobs.blobs.size, 1);
});

test('budget exceeded stops before any external call', async () => {
  const backend = createTestBackend({ config: { dailyBudgetUsd: 0.01 } });
  await backend.store.addUsage(today, { grokCalls: 5, ttsCharacters: 0, bytesStored: 0 }); // 5 x 0.002 = the whole budget
  backend.textGenerator.push(JOKES[0]);
  await assert.rejects(backend.generator.generate(job()), (error) => error.code === 'budget_exceeded');
  assert.equal(backend.textGenerator.prompts.length, 0);
  assert.equal(backend.speech.ssml.length, 0);
  assert.equal(backend.blobs.blobs.size, 0);
});

test('the budget is per UTC day', async () => {
  const backend = createTestBackend({ config: { dailyBudgetUsd: 0.01 } });
  await backend.store.addUsage(today, { grokCalls: 5, ttsCharacters: 0, bytesStored: 0 });
  backend.clock.ms += 86_400_000;
  backend.textGenerator.push(JOKES[0]);
  assert.equal((await backend.generator.generate(job())).text, JOKES[0]);
});

test('Arabic without tashkeel is rejected; the vowelled retry is accepted', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push('يا هلا والله شكلنا بنلف الديرة كلها قبل لا نوصل', ARABIC_JOKES[0]);
  const clip = await backend.generator.generate(job({ language: 'ar-QA', personality: 'abu-nasser' }));
  assert.equal(clip.text, ARABIC_JOKES[0]);
  assert.equal(clip.voice, 'ar-QA-MoazNeural');
  assert.equal(clip.normalizedText, 'يا هلا والله شكلنا بنلف الديره كلها قبل لا نوصل');
  assert.ok(backend.textGenerator.prompts[1].user.includes('rejected: not fully vowelled with tashkeel'));
  assert.ok(backend.speech.ssml[0].startsWith('<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ar-QA">'));
});

test('Arabic that is never vowelled fails the job', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push('يا هلا والله', 'حياك الله يا بطل', 'توكلنا على الله');
  await assert.rejects(
    backend.generator.generate(job({ language: 'ar-QA', personality: 'noura' })),
    (error) => error.code === 'no_acceptable_text' && /tashkeel/.test(error.message),
  );
  assert.equal(backend.speech.ssml.length, 0);
});

test('an unvowelled copy of an existing Arabic line counts as the same line', async () => {
  const backend = createTestBackend();
  const respelled = ARABIC_JOKES[0].replace('الدِّيرَةْ', 'الدِّيرَهْ').replace('يَا', 'يَـا');
  backend.textGenerator.push(ARABIC_JOKES[0], respelled, ARABIC_JOKES[1]);
  await backend.generator.generate(job({ language: 'ar-QA', personality: 'abu-nasser' }));
  const clip = await backend.generator.generate(job({ language: 'ar-QA', personality: 'abu-nasser' }));
  assert.equal(clip.text, ARABIC_JOKES[1]);
  assert.ok(backend.textGenerator.prompts[2].user.includes('rejected: identical to an existing line'));
});

test('text with a distance is rejected', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push('In 300 m you will regret skipping breakfast.', JOKES[4]);
  assert.equal((await backend.generator.generate(job())).text, JOKES[4]);
});

test('only reviewed dictionary entries of the language and its base language reach the SSML', async () => {
  const backend = createTestBackend();
  const entry = (language, term, spoken, reviewed) =>
    backend.store.upsertPronunciation({ language, term, normalizedTerm: term.toLowerCase(), spoken, ipa: null, reviewed, updatedAt: backend.now() });
  await entry('en-US', 'snacks', 'SNAX', true);
  await entry('en', 'journey', 'JUR-nee', true);
  await entry('en-US', 'circuits', 'SIR-kits', false); // not reviewed
  await entry('en-GB', 'predict', 'pre-DICT', true); // other region
  backend.textGenerator.push(JOKES[2]);
  await backend.generator.generate(job());
  assert.ok(backend.speech.ssml[0].includes('>My circuits predict <sub alias="SNAX">snacks</sub> at the end of this <sub alias="JUR-nee">journey</sub>.</voice>'));
});

test('a personality with rate and pitch gets a prosody element', async () => {
  const backend = createTestBackend();
  backend.textGenerator.push(JOKES[0]);
  const clip = await backend.generator.generate(job({ personality: 'old-timer' }));
  assert.equal(clip.voice, 'en-US-AndrewMultilingualNeural');
  assert.ok(backend.speech.ssml[0].includes('<prosody rate="-8%" pitch="-4%">'));
});

test('a job that is not forceNew reuses a clip its device has not heard', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const existing = await backend.seedClip();
  const reused = await backend.generator.generate(job({ forceNew: false, requestedBy: device.deviceId }));
  assert.equal(reused.id, existing.id);
  assert.equal(backend.textGenerator.prompts.length, 0);

  backend.textGenerator.push(JOKES[0]);
  const fresh = await backend.generator.generate(job({ forceNew: true, requestedBy: device.deviceId }));
  assert.notEqual(fresh.id, existing.id);
});

// ---- worker ----------------------------------------------------------------------------------

async function queueJob(backend, overrides = {}) {
  const row = { id: crypto.randomUUID(), status: 'pending', createdAt: backend.now(), ...job(overrides) };
  await backend.store.insertJob(row);
  await backend.queue.send({ jobId: row.id });
  return row.id;
}

test('worker: runs a queued job, marks it done and removes the message', async () => {
  const backend = createTestBackend();
  const jobId = await queueJob(backend);
  backend.textGenerator.push(JOKES[0]);
  assert.equal(await backend.worker.runOnce(), true);
  const row = await backend.store.getJob(jobId);
  assert.deepEqual([row.status, row.attempts, row.error], ['done', 1, null]);
  assert.equal(backend.queue.size(), 0);
  assert.equal(await backend.worker.runOnce(), false, 'empty queue');
});

test('worker: a final failure is recorded on the job and not retried', async () => {
  const backend = createTestBackend({ config: { dailyBudgetUsd: 0 } });
  const jobId = await queueJob(backend);
  await backend.worker.runOnce();
  const row = await backend.store.getJob(jobId);
  assert.equal(row.status, 'failed');
  assert.match(row.error, /^budget_exceeded:/);
  assert.ok(row.finishedAt);
  assert.equal(backend.queue.size(), 0);
});

test('worker: a transient failure is retried after the visibility timeout', async () => {
  const backend = createTestBackend();
  const jobId = await queueJob(backend);
  backend.textGenerator.push(new Error('socket hang up'), JOKES[0]);

  await backend.worker.runOnce();
  let row = await backend.store.getJob(jobId);
  assert.deepEqual([row.status, row.attempts], ['pending', 1]);
  assert.match(row.error, /socket hang up/);
  assert.equal(await backend.worker.runOnce(), false, 'message is invisible until the timeout passes');

  backend.clock.ms += backend.config.jobVisibilityTimeoutSeconds * 1000;
  await backend.worker.runOnce();
  row = await backend.store.getJob(jobId);
  assert.deepEqual([row.status, row.attempts, row.error], ['done', 2, null]);
});

test('worker: a poison message is abandoned after the maximum number of attempts', async () => {
  const backend = createTestBackend({ config: { jobMaxAttempts: 2 } });
  const jobId = await queueJob(backend);
  backend.textGenerator.push(new Error('boom 1'), new Error('boom 2'));
  for (let delivery = 1; delivery <= 3; delivery++) {
    await backend.worker.runOnce();
    backend.clock.ms += backend.config.jobVisibilityTimeoutSeconds * 1000;
  }
  const row = await backend.store.getJob(jobId);
  assert.equal(row.status, 'failed');
  assert.match(row.error, /^poison: gave up after 2 attempts; last error: error: boom 2/);
  assert.equal(backend.textGenerator.prompts.length, 2, 'the third delivery did not call the generator');
  assert.equal(backend.queue.size(), 0);
});

test('worker: a message for a finished or unknown job is dropped', async () => {
  const backend = createTestBackend();
  await backend.queue.send({ jobId: crypto.randomUUID() });
  assert.equal(await backend.worker.runOnce(), true);
  assert.equal(backend.queue.size(), 0);
  assert.equal(backend.textGenerator.prompts.length, 0);
});

test('worker: start and stop run the loop', async () => {
  const backend = createTestBackend({ config: { workerPollSeconds: 0.01 } });
  const jobId = await queueJob(backend);
  backend.textGenerator.push(JOKES[0]);
  backend.worker.start();
  while ((await backend.store.getJob(jobId)).status === 'pending') await new Promise((resolve) => setTimeout(resolve, 5));
  await backend.worker.stop();
  assert.equal((await backend.store.getJob(jobId)).status, 'done');
});

// ---- voice QA script (with fakes; the real run needs real keys) -----------------------------------

test('voice-qa writes one mp3 per event and a review sheet', async () => {
  const outDir = await mkdtemp(path.join(tmpdir(), 'voice-qa-'));
  try {
    // Thirteen acceptable lines and one event whose three attempts all fail validation.
    const lines = EVENT_TYPES.slice(0, 13).map((type, i) => `Line ${'abcdefghijklmn'[i]}${i} about ${type.replace('_', ' ')}, said "nicely".`);
    const textGenerator = scriptedTextGenerator([...lines, '', '', '']);
    const result = await runVoiceQa({ language: 'en-US', personality: 'ava', humourLevel: 1, outDir, textGenerator, speech: fakeSpeech() });
    assert.equal(result.generated, 13);
    assert.equal(result.failed, 1);

    const files = await readdir(outDir);
    assert.equal(files.filter((name) => name.endsWith('.mp3')).length, 13);
    assert.ok((await readFile(path.join(outDir, 'route_start.mp3'), 'utf8')).startsWith('FAKE-MP3:<speak'));

    const sheet = await readFile(result.sheet, 'utf8');
    const rows = sheet.trim().split('\r\n');
    assert.equal(rows.length, 15);
    assert.ok(rows[0].includes('"pronunciation_ok (yes/no)"') && rows[0].includes('"reviewer_notes"'));
    assert.ok(rows[1].startsWith('"route_start.mp3","route_start","en-US","ava","en-US-AvaMultilingualNeural","Line a0 about route start, said ""nicely""."'));
    assert.ok(rows[14].includes('"night_drive"') && rows[14].includes('No acceptable text after 3 attempts'));
    await assert.rejects(runVoiceQa({ language: 'en-US', personality: 'nobody', humourLevel: 1, outDir, textGenerator, speech: fakeSpeech() }), /Unknown language\/personality/);
  } finally {
    await rm(outDir, { recursive: true, force: true });
  }
});
