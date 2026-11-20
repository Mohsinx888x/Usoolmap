#!/usr/bin/env node
// Human listening test. Generates one sample clip per event type for a language and personality
// with the REAL text and speech services, writes the mp3 files and a CSV review sheet to an
// output folder, and stores nothing in the cloud. A native speaker listens to every file and
// fills in the empty columns; mispronounced words then go into the pronunciation dictionary
// (PUT /v1/admin/pronunciation). This is the only way dialect quality gets checked.
//
//   XAI_API_KEY=... AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=... \
//     node scripts/voice-qa.js --language ar-QA --personality abu-nasser [--humour 2] [--out ./qa-out]
//
// With DATABASE_URL set, the reviewed dictionary entries from that database are applied.

import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { createAzureSpeechSynthesizer } from '../src/azureSpeech.js';
import { createMemoryBlobStore } from '../src/blob.js';
import { loadConfig } from '../src/config.js';
import { EVENT_TYPES } from '../src/events.js';
import { createGenerator } from '../src/generator.js';
import { createGrokTextGenerator } from '../src/grok.js';
import { createMemoryStore } from '../src/store/memory.js';
import { createPostgresStore } from '../src/store/postgres.js';
import { createUsage } from '../src/usage.js';
import { LANGUAGES, findPersonality } from '../src/voices.js';

const COLUMNS = ['file', 'eventType', 'language', 'personality', 'voice', 'text', 'error',
  'pronunciation_ok (yes/no)', 'sounds_like_the_dialect (yes/no)', 'mispronounced_words', 'reviewer_notes'];
const csvCell = (value) => `"${String(value ?? '').replaceAll('"', '""')}"`;

/**
 * @param {{language: string, personality: string, humourLevel: number, outDir: string,
 *   textGenerator: object, speech: object, entries?: object[], config?: object}} options
 *   `entries`: pronunciation dictionary entries to apply (only reviewed ones are used)
 * @returns {Promise<{sheet: string, generated: number, failed: number}>}
 */
export async function runVoiceQa({ language, personality, humourLevel, outDir, textGenerator, speech, entries = [], config = loadConfig({}) }) {
  if (!findPersonality(language, personality)) {
    const known = Object.entries(LANGUAGES).map(([tag, l]) => `${tag}: ${l.personalities.map((p) => p.id).join(', ')}`);
    throw new Error(`Unknown language/personality "${language}/${personality}". Known:\n${known.join('\n')}`);
  }
  const store = createMemoryStore();
  for (const entry of entries) await store.upsertPronunciation(entry);
  const blobs = createMemoryBlobStore();
  // A review run is started deliberately by an operator, so the daily budget does not apply.
  const qaConfig = { ...config, dailyBudgetUsd: Infinity };
  const generator = createGenerator({ store, blobs, textGenerator, speech, config: qaConfig, usage: createUsage({ store, config: qaConfig }) });

  await mkdir(outDir, { recursive: true });
  const rows = [];
  for (const eventType of EVENT_TYPES) {
    const row = { eventType, language, personality };
    try {
      const clip = await generator.generate({ eventType, language, personality, humourLevel, requestedBy: null, forceNew: true });
      row.file = `${eventType}.mp3`;
      row.voice = clip.voice;
      row.text = clip.text;
      await writeFile(path.join(outDir, row.file), blobs.blobs.get(clip.blobPath).data);
    } catch (error) {
      row.error = error.message;
    }
    rows.push(row);
  }

  const sheet = path.join(outDir, `review-${language}-${personality}.csv`);
  const lines = [COLUMNS, ...rows.map((row) => COLUMNS.map((column) => row[column]))].map((cells) => cells.map(csvCell).join(','));
  // The byte order mark makes spreadsheet programs open the Arabic text as UTF-8.
  await writeFile(sheet, `﻿${lines.join('\r\n')}\r\n`, 'utf8');
  return { sheet, generated: rows.filter((r) => r.file).length, failed: rows.filter((r) => r.error).length };
}

async function main() {
  const { values } = parseArgs({
    options: {
      language: { type: 'string' }, personality: { type: 'string' },
      humour: { type: 'string', default: '2' }, out: { type: 'string', default: './qa-out' },
    },
  });
  const config = loadConfig();
  const missing = ['XAI_API_KEY', 'AZURE_SPEECH_KEY', 'AZURE_SPEECH_REGION'].filter((name) => !process.env[name]);
  if (!values.language || !values.personality || missing.length > 0) {
    console.error('Usage: node scripts/voice-qa.js --language ar-QA --personality abu-nasser [--humour 1|2|3] [--out ./qa-out]');
    if (missing.length > 0) console.error(`Missing environment variables: ${missing.join(', ')}`);
    process.exit(2);
  }

  let entries = [];
  if (config.databaseUrl) {
    const database = createPostgresStore(config.databaseUrl);
    entries = await database.listPronunciation([values.language, values.language.split('-')[0]], true);
    await database.close();
  }
  const result = await runVoiceQa({
    language: values.language, personality: values.personality, humourLevel: Number(values.humour), outDir: values.out,
    textGenerator: createGrokTextGenerator({ apiKey: config.xaiApiKey, model: config.grokModel }),
    speech: createAzureSpeechSynthesizer({ key: config.speechKey, region: config.speechRegion }),
    entries, config,
  });
  console.log(`${result.generated} clips generated, ${result.failed} failed. Review sheet: ${result.sheet}`);
}

if (import.meta.url === `file://${process.argv[1]}`) await main();
