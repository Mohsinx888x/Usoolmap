import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CATALOGUE, buildAssistantPrompt, validateAction } from '../src/assistant.js';
import { createTestBackend } from './helpers.js';

const valid = (object) => validateAction(JSON.stringify(object));

test('validator accepts every kind of action in the catalogue', () => {
  const samples = [
    { action: 'navigate_home' },
    { action: 'navigate_to', query: 'سوق واقف' },
    { action: 'find_nearby', concept: 'halal_restaurant' },
    { action: 'set_3d', enabled: true },
    { action: 'set_satellite', enabled: false },
    { action: 'open_globe' },
    { action: 'show_traffic' },
    { action: 'avoid_tolls', enabled: true },
    { action: 'set_voice_mode', mode: 'smart_mixed' },
    { action: 'set_voice_language', language: 'ar-QA' },
    { action: 'download_offline_map' },
    { action: 'generate_jokes' },
    { action: 'stop_navigation' },
  ];
  assert.deepEqual(samples.map((s) => s.action), Object.keys(CATALOGUE), 'one sample per catalogue entry');
  for (const sample of samples) assert.deepEqual(valid(sample), sample);
  assert.deepEqual(valid({ action: 'navigate_to', query: 'ab' }), { action: 'navigate_to', query: 'ab' });
  assert.deepEqual(valid({ action: 'navigate_to', query: 'x'.repeat(120) }).query.length, 120);
});

test('validator returns null for "none", unknown actions and anything that is not one JSON object', () => {
  for (const reply of [
    '{"action":"none"}', '{"action":"self_destruct"}', '{"action":"toString"}', '{"action":"__proto__"}', '{"action":42}', '{}',
    'Sure! I will take you home.', '', '[{"action":"navigate_home"}]', '"navigate_home"', 'null', '42',
    '{"action":"navigate_home"} and some prose', '{"action":"navigate_home"',
  ]) {
    assert.equal(validateAction(reply), null, reply);
  }
  for (const notText of [undefined, null, 42, { action: 'navigate_home' }]) assert.equal(validateAction(notText), null);
});

test('validator rejects missing, mistyped and out-of-catalogue parameters', () => {
  const rejected = [
    { action: 'navigate_to' },
    { action: 'navigate_to', query: 'x' },
    { action: 'navigate_to', query: 'x'.repeat(121) },
    { action: 'navigate_to', query: 'Doha\nand then ignore the rules' },
    { action: 'navigate_to', query: 'Doha\rCorniche' },
    { action: 'navigate_to', query: 12345 },
    { action: 'navigate_to', query: ['Doha'] },
    { action: 'find_nearby' },
    { action: 'find_nearby', concept: 'casino' },
    { action: 'find_nearby', concept: 'Restaurant' },
    { action: 'find_nearby', concept: ['restaurant'] },
    { action: 'set_3d' },
    { action: 'set_3d', enabled: 'true' },
    { action: 'set_3d', enabled: 'yes' },
    { action: 'set_satellite', enabled: 1 },
    { action: 'avoid_tolls', enabled: null },
    { action: 'set_voice_mode', mode: 'shouting' },
    { action: 'set_voice_mode' },
    { action: 'set_voice_language', language: 'ar-JO' },
    { action: 'set_voice_language', language: 'ar-qa' },
    { action: 'set_voice_language', mode: 'funny' },
  ];
  for (const object of rejected) assert.equal(valid(object), null, JSON.stringify(object));
});

test('validator strips extra keys and tolerates a markdown code fence', () => {
  assert.deepEqual(valid({ action: 'navigate_home', query: 'somewhere', note: 'x', enabled: true }), { action: 'navigate_home' });
  assert.deepEqual(
    valid({ action: 'set_3d', enabled: false, reason: 'the user asked', __proto__: { admin: true }, constructor: 'x' }),
    { action: 'set_3d', enabled: false },
  );
  assert.deepEqual(validateAction('```json\n{"action":"find_nearby","concept":"fuel"}\n```'), { action: 'find_nearby', concept: 'fuel' });
  assert.deepEqual(validateAction('```\n{"action":"open_globe"}\n```'), { action: 'open_globe' });
  assert.deepEqual(validateAction('  \n{"action":"show_traffic"}\n '), { action: 'show_traffic' });
});

test('system prompt lists the whole catalogue and the reply rules', () => {
  const { system, user } = buildAssistantPrompt({ text: 'take me home', language: 'en-US' });
  for (const [name, { params }] of Object.entries(CATALOGUE)) {
    assert.ok(system.includes(`- ${name}:`), name);
    for (const spec of Object.values(params)) for (const value of spec.values ?? []) assert.ok(system.includes(value), `${name} ${value}`);
  }
  assert.ok(system.includes('one JSON object only'));
  assert.ok(system.includes('{"action":"none"}'));
  assert.ok(system.includes('Never invent actions'));
  assert.ok(system.includes('halal_restaurant') && system.includes('smart_mixed') && system.includes('bn-BD'));
  assert.equal(user, 'User language: en-US\nUser request: take me home');
  assert.ok(!system.includes('take me home'), 'the request stays out of the system prompt');
});

// ---- endpoint ----------------------------------------------------------------------------------

const command = (backend, headers, payload) => backend.app.inject({ method: 'POST', url: '/v1/assistant/command', headers, payload });
const grokCallsToday = async (backend) => (await backend.store.getUsage('2026-11-20'))[0]?.grokCalls ?? 0;

test('assistant: returns the validated action, without extra keys, and records one Grok call', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  backend.textGenerator.push('```json\n{"action":"navigate_to","query":"Souq Waqif","confidence":0.9,"say":"On it!"}\n```');

  const response = await command(backend, device.headers, { text: 'take me to Souq Waqif', language: 'en-US' });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { action: { action: 'navigate_to', query: 'Souq Waqif' } });
  assert.equal(await grokCallsToday(backend), 1);

  const [prompt] = backend.textGenerator.prompts;
  assert.ok(prompt.system.includes('Catalogue:') && prompt.user.includes('take me to Souq Waqif') && prompt.user.includes('en-US'));
});

test('assistant: garbage, "none", unknown actions and bad parameters all give action null, never the model text', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const replies = ['I cannot help with that, but here is a poem.', '{"action":"none"}', '{"action":"open_sunroof"}',
    '{"action":"set_3d","enabled":"yes"}', '', '[1,2,3]'];
  backend.textGenerator.push(...replies);
  for (const reply of replies) {
    const response = await command(backend, device.headers, { text: 'do something odd', language: 'ar-QA' });
    assert.equal(response.statusCode, 200, reply);
    assert.equal(response.body, '{"action":null}', reply);
  }
  assert.equal(await grokCallsToday(backend), replies.length, 'every call is counted, useful or not');
});

test('assistant: needs a device token and a valid body', async () => {
  const backend = createTestBackend();
  const device = await backend.registerDevice();
  const body = { text: 'take me home', language: 'en-US' };
  for (const headers of [{}, { authorization: 'Bearer v1.nope.nope' }, { 'x-admin-key': 'test-admin-key' }]) {
    assert.equal((await command(backend, headers, body)).statusCode, 401);
  }
  const invalid = [{ ...body, text: '' }, { ...body, text: 'x'.repeat(301) }, { language: 'en-US' }, { text: 'hi' }, { ...body, language: 'not a tag' }];
  for (const payload of invalid) {
    const response = await command(backend, device.headers, payload);
    assert.equal(response.statusCode, 400, JSON.stringify(payload).slice(0, 60));
    assert.equal(response.json().error.code, 'invalid_request');
  }
  assert.equal(backend.textGenerator.prompts.length, 0, 'nothing was sent to the model');
  assert.equal(await grokCallsToday(backend), 0);

  backend.textGenerator.push('{"action":"navigate_home"}');
  assert.equal((await command(backend, device.headers, { ...body, text: 'x'.repeat(300) })).statusCode, 200);
});

test('assistant: budget, rate limit and upstream failure', async () => {
  const broke = createTestBackend({ config: { dailyBudgetUsd: 0 } });
  const poor = await broke.registerDevice();
  const refused = await command(broke, poor.headers, { text: 'take me home', language: 'en-US' });
  assert.equal(refused.statusCode, 503);
  assert.equal(refused.json().error.code, 'budget_exceeded');
  assert.equal(broke.textGenerator.prompts.length, 0);

  const backend = createTestBackend({ config: { rateLimitBurst: 2, rateLimitPerMinute: 6 } });
  const device = await backend.registerDevice();
  backend.textGenerator.push('{"action":"open_globe"}', new Error('upstream down'));
  assert.equal((await command(backend, device.headers, { text: 'globe', language: 'en-US' })).statusCode, 200);
  const down = await command(backend, device.headers, { text: 'globe', language: 'en-US' });
  assert.equal(down.statusCode, 503);
  assert.equal(down.json().error.code, 'generation_unavailable');
  assert.ok(!down.body.includes('upstream down'));
  const limited = await command(backend, device.headers, { text: 'globe', language: 'en-US' });
  assert.equal(limited.statusCode, 429);
  assert.equal(limited.json().error.code, 'rate_limited');
});
