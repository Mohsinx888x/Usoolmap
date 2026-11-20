import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseRange } from '../src/app.js';
import { normalizeArabic, tashkeelRatio } from '../src/arabic.js';
import { bearerToken, hashInstallId, safeEqual, signDeviceToken, verifyDeviceToken } from '../src/auth.js';
import { createAzureSpeechSynthesizer } from '../src/azureSpeech.js';
import { loadConfig, missingSettings } from '../src/config.js';
import { EVENTS, EVENT_TYPES } from '../src/events.js';
import { validateText } from '../src/generator.js';
import { createGrokTextGenerator } from '../src/grok.js';
import { buildSsml } from '../src/pronunciation.js';
import { buildPrompt } from '../src/prompt.js';
import { createRateLimiter } from '../src/ratelimit.js';
import { jaccard, normalize, openingPhrase, shingles, similarity } from '../src/similarity.js';
import { estimateCostUsd } from '../src/usage.js';
import { LANGUAGES, resolveLanguage } from '../src/voices.js';

// ---- similarity and normalisation ---------------------------------------------------------------

test('normalize strips case, punctuation and extra spaces', () => {
  assert.equal(normalize('  Hello,   WORLD!! It\'s… fine. '), 'hello world it s fine');
});

test('normalize strips Arabic diacritics and tatweel and unifies alef, ya and ta marbuta', () => {
  assert.equal(normalize('الدَّوْحَة'), normalize('الدوحه'));
  assert.equal(normalize('أَهْلاً إِلَى آخِر'), 'اهلا الي اخر');
  assert.equal(normalize('مـــرحـــبا'), 'مرحبا');
  assert.equal(normalize('مُسْتَشْفَى، يَا سَلَام!'), 'مستشفي يا سلام');
  assert.equal(normalizeArabic('ٱلْكِتَاب'), 'الكتاب');
});

test('openingPhrase is the first two normalised words', () => {
  assert.equal(openingPhrase('Well, WELL: look who is lost'), 'well well');
  assert.equal(openingPhrase('يَا هَلَا وَاللهْ'), 'يا هلا');
  assert.equal(openingPhrase('Oops'), 'oops');
});

test('similarity: identical is 1, unrelated is low, a reworded copy is high', () => {
  const a = normalize('Recalculating, because apparently the scenic route chose us today.');
  const b = normalize('Recalculating because apparently the scenic route chose us again today!');
  const c = normalize('My circuits predict snacks at the end of this journey.');
  assert.equal(similarity(a, a), 1);
  assert.ok(similarity(a, b) >= 0.55, `reworded copy scored ${similarity(a, b)}`);
  assert.ok(similarity(a, c) < 0.2, `unrelated lines scored ${similarity(a, c)}`);
});

test('similarity ignores tashkeel in Arabic', () => {
  const vowelled = normalize('يَا هَلَا وَاللهْ، الطَّرِيقْ طَوِيلْ');
  const bare = normalize('يا هلا والله الطريق طويل');
  assert.equal(similarity(vowelled, bare), 1);
});

test('shingles are word bigrams plus character trigrams; jaccard handles empty sets', () => {
  const set = shingles('ab cd');
  assert.ok(set.has('w:ab cd') && set.has('c:ab ') && set.has('c:b c'));
  assert.equal(jaccard(new Set(), new Set()), 1);
  assert.equal(jaccard(new Set(['x']), new Set(['y'])), 0);
});

// ---- Arabic ----------------------------------------------------------------------------------

test('tashkeelRatio is marks divided by Arabic letters', () => {
  assert.equal(tashkeelRatio('كَتَبَ'), 1);
  assert.equal(tashkeelRatio('كتب'), 0);
  assert.equal(tashkeelRatio('كَتب'), 1 / 3);
  assert.equal(tashkeelRatio('hello 123'), 0);
  assert.ok(tashkeelRatio('يَا هَلَا وَاللهْ، الطَّرِيقْ طَوِيلْ') >= 0.4);
});

test('validateText enforces length, units, digits and tashkeel', () => {
  assert.equal(validateText('A perfectly fine line.', 'en-US'), null);
  assert.match(validateText('', 'en-US'), /empty/);
  assert.match(validateText('x'.repeat(301), 'en-US'), /too long/);
  assert.match(validateText('Only 200 m to go, champ.', 'en-US'), /digits with a unit/);
  assert.match(validateText('Just 5km of this, promise.', 'en-US'), /digits with a unit/);
  assert.equal(validateText('That was turn number 3 today, my friend.', 'en-US'), null);
  assert.match(validateText('يا هلا والله الطريق طويل', 'ar-QA'), /tashkeel/);
  assert.match(validateText('بَاقِي ٣ دَقَايِقْ يَا بَطَلْ', 'ar-QA'), /digits/);
  assert.equal(validateText('يَا هَلَا وَاللهْ، الطَّرِيقْ طَوِيلْ', 'ar-QA'), null);
  const longArabic = 'يَا هَلَا '.repeat(40).trim(); // 359 characters: too long for English, fine for Arabic
  assert.equal(validateText(longArabic, 'ar-QA'), null);
  assert.match(validateText('يَا هَلَا '.repeat(60), 'ar-QA'), /too long/);
});

// ---- pronunciation -> SSML ----------------------------------------------------------------------

const ssml = (text, entries = [], extra = {}) => buildSsml({ text, language: 'ar-QA', voice: 'ar-QA-MoazNeural', entries, ...extra });
const reviewed = (term, spoken, more = {}) => ({ term, spoken, ipa: null, reviewed: true, ...more });

test('SSML has the speak and voice wrappers and escapes the text', () => {
  const doc = buildSsml({ text: 'Tom & "Jerry" <drive> it\'s', language: 'en-US', voice: 'en-US-AvaMultilingualNeural' });
  assert.equal(
    doc,
    '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US">' +
      '<voice name="en-US-AvaMultilingualNeural">Tom &amp; &quot;Jerry&quot; &lt;drive&gt; it&apos;s</voice></speak>',
  );
});

test('prosody appears only when the personality defines rate or pitch', () => {
  assert.ok(!ssml('مرحبا').includes('<prosody'));
  assert.ok(ssml('مرحبا', [], { rate: '-8%', pitch: '-4%' }).includes('<prosody rate="-8%" pitch="-4%">مرحبا</prosody>'));
  assert.ok(ssml('مرحبا', [], { rate: '-8%' }).includes('<prosody rate="-8%">مرحبا</prosody>'));
});

test('dictionary terms become sub or phoneme elements, with escaped attributes', () => {
  const doc = buildSsml({
    text: 'We love Doha & Lusail',
    language: 'en-US',
    voice: 'v',
    entries: [reviewed('doha', 'DOE-"ha"'), { term: 'Lusail', spoken: null, ipa: 'luːˈsajl', reviewed: true }],
  });
  assert.ok(doc.includes('We love <sub alias="DOE-&quot;ha&quot;">Doha</sub> &amp; <phoneme alphabet="ipa" ph="luːˈsajl">Lusail</phoneme>'));
});

test('only reviewed entries with a pronunciation are applied', () => {
  const entries = [
    { term: 'Doha', spoken: 'DOE-ha', ipa: null, reviewed: false },
    { term: 'Lusail', spoken: null, ipa: null, reviewed: true },
  ];
  const doc = buildSsml({ text: 'Doha and Lusail', language: 'en-US', voice: 'v', entries });
  assert.ok(doc.includes('>Doha and Lusail</voice>'));
});

test('matching is whole-word and the longest term wins', () => {
  const entries = [reviewed('سوق', 'سُوق'), reviewed('سوق واقف', 'سُوق وَاقِف')];
  assert.ok(ssml('رحنا سوق واقف أمس', entries).includes('رحنا <sub alias="سُوق وَاقِف">سوق واقف</sub> أمس'));
  assert.ok(ssml('رحنا سوق السمك', entries).includes('رحنا <sub alias="سُوق">سوق</sub> السمك'));
  // "سوق" inside a longer word is left alone.
  assert.ok(!ssml('هذا تسويق زين', entries).includes('<sub'));
  const english = buildSsml({ text: 'Dohan is not Doha', language: 'en-US', voice: 'v', entries: [reviewed('Doha', 'DOE-ha')] });
  assert.ok(english.includes('Dohan is not <sub alias="DOE-ha">Doha</sub>'));
});

test('Arabic terms match with or without tashkeel on either side', () => {
  const bareTerm = [reviewed('الدوحة', 'الدَّوْحَة')];
  // Vowelled text, bare term: the marks after the last letter stay inside the element.
  assert.ok(ssml('وَصَلْنَا الدَّوْحَةَ يَا شَبَابْ', bareTerm).includes('وَصَلْنَا <sub alias="الدَّوْحَة">الدَّوْحَةَ</sub> يَا شَبَابْ'));
  // Bare text, vowelled term, and a different ta marbuta / ha spelling.
  const vowelledTerm = [reviewed('الدَّوْحَة', 'الدَّوْحَة')];
  assert.ok(ssml('وصلنا الدوحه يا شباب', vowelledTerm).includes('وصلنا <sub alias="الدَّوْحَة">الدوحه</sub> يا شباب'));
  // Every occurrence is replaced.
  assert.equal(ssml('الدوحة ثم الدوحة', bareTerm).match(/<sub /g).length, 2);
});

// ---- voices ----------------------------------------------------------------------------------

test('every language has two or three well-formed personalities with unique ids', () => {
  for (const [tag, { name, personalities }] of Object.entries(LANGUAGES)) {
    assert.ok(name && personalities.length >= 2 && personalities.length <= 3, tag);
    assert.equal(new Set(personalities.map((p) => p.id)).size, personalities.length, tag);
    for (const p of personalities) {
      assert.ok(p.voice.startsWith(`${tag}-`) && p.voice.endsWith('Neural'), `${tag} ${p.voice}`);
      assert.ok(['male', 'female'].includes(p.gender) && p.label && p.description);
    }
  }
  assert.equal(Object.keys(LANGUAGES).length, 23);
  assert.equal(LANGUAGES['ar-QA'].personalities[0].voice, 'ar-QA-MoazNeural');
});

test('resolveLanguage: exact, same base language, then US English', () => {
  const exact = resolveLanguage('ar-QA');
  assert.deepEqual([exact.language, exact.fallback, exact.fallbackNote], ['ar-QA', false, null]);
  assert.equal(resolveLanguage('ar-qa').language, 'ar-QA');

  const jordan = resolveLanguage('ar-JO');
  assert.deepEqual([jordan.requestedLanguage, jordan.language, jordan.fallback], ['ar-JO', 'ar-SA', true]);
  assert.equal(jordan.fallbackNote, 'No voice for ar-JO; using the Saudi Arabic voice.');

  assert.equal(resolveLanguage('es-MX').language, 'es-ES');
  assert.equal(resolveLanguage('en-AU').language, 'en-US');
  assert.equal(resolveLanguage('ar').language, 'ar-SA');

  const swahili = resolveLanguage('sw-KE');
  assert.deepEqual([swahili.language, swahili.fallback], ['en-US', true]);
  assert.equal(swahili.fallbackNote, 'No voice for sw-KE; using the US English voice.');
  assert.equal(resolveLanguage('not a tag').language, 'en-US');
});

// ---- auth ------------------------------------------------------------------------------------

test('device tokens verify, and reject tampering, other secrets and garbage', () => {
  const id = '3f2b8c1e-0d4a-4f6b-9c7e-1a2b3c4d5e6f';
  const token = signDeviceToken(id, 'secret-a');
  assert.equal(verifyDeviceToken(token, 'secret-a'), id);
  assert.equal(verifyDeviceToken(token, 'secret-b'), null);

  const [version, , signature] = token.split('.');
  assert.equal(verifyDeviceToken(`${version}.00000000-0000-4000-8000-000000000000.${signature}`, 'secret-a'), null);
  assert.equal(verifyDeviceToken(`${token.slice(0, -1)}${token.endsWith('A') ? 'B' : 'A'}`, 'secret-a'), null);
  for (const garbage of ['', 'v1.only-two', 'v2.a.b', undefined, null, 42]) {
    assert.equal(verifyDeviceToken(garbage, 'secret-a'), null);
  }
});

test('safeEqual, bearerToken and hashInstallId', () => {
  assert.ok(safeEqual('abc', 'abc'));
  assert.ok(!safeEqual('abc', 'abd'));
  assert.ok(!safeEqual('abc', 'abcd'));
  assert.ok(!safeEqual(undefined, 'abc'));
  assert.equal(bearerToken('Bearer abc.def'), 'abc.def');
  assert.equal(bearerToken('Basic abc'), null);
  assert.equal(bearerToken(undefined), null);
  assert.equal(hashInstallId('install-1', 's'), hashInstallId('install-1', 's'));
  assert.notEqual(hashInstallId('install-1', 's'), hashInstallId('install-2', 's'));
  assert.ok(!hashInstallId('install-1', 's').includes('install-1'));
});

// ---- rate limiter ------------------------------------------------------------------------------

test('token bucket: burst, refusal with retry hint, refill, per-key isolation', () => {
  let ms = 0;
  const limiter = createRateLimiter({ capacity: 3, refillPerSecond: 0.5, now: () => ms });
  assert.deepEqual([1, 2, 3].map(() => limiter.take('a').ok), [true, true, true]);
  assert.deepEqual(limiter.take('a'), { ok: false, retryAfterSeconds: 2 });
  assert.ok(limiter.take('b').ok, 'another key has its own bucket');
  ms += 2000; // one token refilled
  assert.ok(limiter.take('a').ok);
  assert.ok(!limiter.take('a').ok);
  ms += 60_000; // never above capacity
  assert.deepEqual([1, 2, 3, 4].map(() => limiter.take('a').ok), [true, true, true, false]);
});

test('token bucket forgets idle keys once it holds too many', () => {
  let ms = 0;
  const limiter = createRateLimiter({ capacity: 2, refillPerSecond: 1, now: () => ms, maxKeys: 3 });
  for (const key of ['a', 'b', 'c']) limiter.take(key);
  ms += 10_000;
  limiter.take('d');
  assert.equal(limiter.size(), 1);
});

// ---- prompt ----------------------------------------------------------------------------------

const prompt = (overrides = {}) =>
  buildPrompt({ eventType: 'reroute', language: 'en-US', personality: 'andy', humourLevel: 2, ...overrides });

test('prompt states the rules, the situation, the personality and the humour level', () => {
  const { system, user } = prompt({ humourLevel: 3 });
  for (const rule of ['at most about 22 words', 'ORIGINAL', 'NOT be a translation', 'Family-friendly', 'religion, politics, nationality, gender',
    'Never encourage speeding', 'directions, distances']) {
    assert.ok(system.includes(rule), rule);
  }
  assert.ok(user.includes(EVENTS.reroute.situation));
  assert.ok(user.includes('US English (en-US)'));
  assert.ok(user.includes('Andy') && user.includes('cheeky'));
});

test('prompt lists the openings and jokes to avoid, and earlier rejections', () => {
  const { user } = prompt({
    recentTexts: ['Old joke number one.', 'Old joke number two.'],
    recentOpenings: ['old joke', 'well well'],
    rejected: [{ text: 'Old joke again.', reason: 'identical to an existing line' }],
  });
  assert.ok(user.includes('Do NOT begin with any of these openings'));
  assert.ok(user.includes('- old joke') && user.includes('- well well'));
  assert.ok(user.includes('- Old joke number one.') && user.includes('- Old joke number two.'));
  assert.ok(user.includes('"Old joke again." (rejected: identical to an existing line)'));

  const fresh = prompt().user;
  assert.ok(!fresh.includes('openings') && !fresh.includes('rejected'));
});

test('Gulf dialect and tashkeel instructions appear only for the right languages', () => {
  for (const tag of ['ar-QA', 'ar-AE', 'ar-SA', 'ar-KW', 'ar-BH', 'ar-OM']) {
    const { user } = prompt({ language: tag, personality: LANGUAGES[tag].personalities[0].id });
    assert.ok(user.includes('Gulf (Khaleeji)'), tag);
    assert.ok(user.includes('Do NOT write Modern Standard Arabic'), tag);
    assert.ok(user.includes('fully vowelled with tashkeel'), tag);
    assert.ok(user.includes('number as words'), tag);
  }
  const egyptian = prompt({ language: 'ar-EG', personality: 'mona' }).user;
  assert.ok(egyptian.includes('Egyptian colloquial Arabic') && egyptian.includes('tashkeel'));
  assert.ok(!egyptian.includes('Gulf') && !egyptian.includes('Khaleeji'));

  for (const [tag, id] of [['en-US', 'ava'], ['hi-IN', 'swara'], ['ur-PK', 'asad'], ['fr-FR', 'henri']]) {
    const { system, user } = prompt({ language: tag, personality: id });
    for (const word of ['Gulf', 'Khaleeji', 'tashkeel', 'Modern Standard Arabic']) {
      assert.ok(!user.includes(word) && !system.includes(word), `${tag} ${word}`);
    }
  }
});

test('events: all fourteen are described; careful ones do not ask for a punchline', () => {
  assert.equal(EVENT_TYPES.length, 14);
  for (const type of EVENT_TYPES) assert.ok(EVENTS[type].situation.length > 20 && typeof EVENTS[type].humour === 'boolean', type);
  const speeding = prompt({ eventType: 'speeding', humourLevel: 3 }).user;
  assert.ok(speeding.includes('not a moment for a punchline') && !speeding.includes('cheeky'));
  assert.ok(speeding.includes('Never make speeding sound fun'));
});

// ---- small pieces ------------------------------------------------------------------------------

test('parseRange', () => {
  assert.deepEqual(parseRange('bytes=0-', 100), { start: 0, end: 99 });
  assert.deepEqual(parseRange('bytes=40-', 100), { start: 40, end: 99 });
  assert.deepEqual(parseRange('bytes=10-19', 100), { start: 10, end: 19 });
  assert.deepEqual(parseRange('bytes=90-500', 100), { start: 90, end: 99 });
  assert.deepEqual(parseRange('bytes=-10', 100), { start: 90, end: 99 });
  assert.equal(parseRange('bytes=100-', 100), 'unsatisfiable');
  assert.equal(parseRange('bytes=20-10', 100), 'unsatisfiable');
  assert.equal(parseRange(undefined, 100), null);
  assert.equal(parseRange('items=0-5', 100), null);
  assert.equal(parseRange('bytes=0-5,10-15', 100), null);
});

test('config defaults, overrides and required settings per role', () => {
  const defaults = loadConfig({});
  assert.deepEqual(
    [defaults.role, defaults.port, defaults.grokModel, defaults.nonRepeatDays, defaults.dailyDeviceQuota, defaults.maxJobsPerRequest,
      defaults.starterPerEvent, defaults.similarityThreshold, defaults.priceGrokPerCallUsd, defaults.priceTtsPerMillionCharsUsd],
    ['all', 8080, 'latest', 90, 20, 3, 3, 0.55, 0.002, 16],
  );
  assert.equal(loadConfig({ NON_REPEAT_DAYS: '30' }).nonRepeatDays, 30);
  assert.throws(() => loadConfig({ PORT: 'eighty' }), /PORT must be a number/);

  assert.deepEqual(
    missingSettings(loadConfig({ ROLE: 'api', TOKEN_SECRET: 's', DATABASE_URL: 'd', AZURE_STORAGE_ACCOUNT: 'a', APP_KEY: 'k' })),
    ['XAI_API_KEY', 'ADMIN_KEY'],
  );
  assert.deepEqual(
    missingSettings(loadConfig({ ROLE: 'worker', TOKEN_SECRET: 's', DATABASE_URL: 'd', AZURE_STORAGE_CONNECTION_STRING: 'c', XAI_API_KEY: 'x' })),
    ['AZURE_SPEECH_KEY', 'AZURE_SPEECH_REGION'],
  );
});

test('estimateCostUsd uses the configured unit prices', () => {
  const prices = { priceGrokPerCallUsd: 0.002, priceTtsPerMillionCharsUsd: 16 };
  assert.equal(estimateCostUsd({ grokCalls: 100, ttsCharacters: 250_000 }, prices), 4.2);
});

// ---- outbound clients against a fake fetch (never the real services) -----------------------------

function fakeFetch(responses) {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, init });
    const next = responses.shift();
    if (next instanceof Error) throw next;
    return next;
  };
  return { fetch, calls };
}
const noSleep = async () => {};

test('Grok client: request shape, and retry on 429 and 5xx', async () => {
  const { fetch, calls } = fakeFetch([
    new Response('slow down', { status: 429 }),
    new Response('oops', { status: 503 }),
    Response.json({ choices: [{ message: { content: 'A fresh line.' } }] }),
  ]);
  const grok = createGrokTextGenerator({ apiKey: 'test-key', model: 'latest', fetch, sleep: noSleep });
  assert.equal(await grok.generate({ system: 'SYS', user: 'USR' }), 'A fresh line.');
  assert.equal(calls.length, 3);
  assert.equal(calls[0].url, 'https://api.x.ai/v1/chat/completions');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer test-key');
  const body = JSON.parse(calls[0].init.body);
  assert.equal(body.model, 'latest');
  assert.deepEqual(body.messages, [{ role: 'system', content: 'SYS' }, { role: 'user', content: 'USR' }]);
});

test('Grok client: no retry on 4xx, gives up after repeated failures, retries network errors', async () => {
  const bad = fakeFetch([new Response('bad key', { status: 401 })]);
  await assert.rejects(createGrokTextGenerator({ apiKey: 'k', model: 'm', fetch: bad.fetch, sleep: noSleep }).generate({ system: '', user: '' }), /Grok responded 401/);
  assert.equal(bad.calls.length, 1);

  const down = fakeFetch([1, 2, 3, 4, 5].map(() => new Response('down', { status: 500 })));
  await assert.rejects(createGrokTextGenerator({ apiKey: 'k', model: 'm', fetch: down.fetch, sleep: noSleep }).generate({ system: '', user: '' }), /Grok responded 500/);
  assert.equal(down.calls.length, 4);

  const flaky = fakeFetch([new TypeError('fetch failed'), Response.json({ choices: [{ message: { content: 'ok' } }] })]);
  assert.equal(await createGrokTextGenerator({ apiKey: 'k', model: 'm', fetch: flaky.fetch, sleep: noSleep }).generate({ system: '', user: '' }), 'ok');
});

test('Azure Speech client: endpoint, headers, body, retry, and empty audio is an error', async () => {
  const { fetch, calls } = fakeFetch([new Response('busy', { status: 503 }), new Response(Buffer.from('mp3-bytes'))]);
  const speech = createAzureSpeechSynthesizer({ key: 'speech-key', region: 'qatarcentral', fetch, sleep: noSleep });
  const audio = await speech.synthesize('<speak>hi</speak>');
  assert.equal(audio.toString(), 'mp3-bytes');
  assert.equal(calls.length, 2);
  assert.equal(calls[1].url, 'https://qatarcentral.tts.speech.microsoft.com/cognitiveservices/v1');
  assert.equal(calls[1].init.headers['Ocp-Apim-Subscription-Key'], 'speech-key');
  assert.equal(calls[1].init.headers['Content-Type'], 'application/ssml+xml');
  assert.equal(calls[1].init.headers['X-Microsoft-OutputFormat'], 'audio-24khz-48kbitrate-mono-mp3');
  assert.equal(calls[1].init.body, '<speak>hi</speak>');

  const empty = fakeFetch([new Response('')]);
  await assert.rejects(createAzureSpeechSynthesizer({ key: 'k', region: 'r', fetch: empty.fetch, sleep: noSleep }).synthesize('<speak/>'), /empty audio/);
});
