// Entry point. ROLE=api serves HTTP, ROLE=worker consumes the queue, ROLE=all (default) does both
// in one process, which is how the single Container App runs.

import pino from 'pino';
import { buildApp } from './app.js';
import { createAzureSpeechSynthesizer } from './azureSpeech.js';
import { createAzureBlobStore } from './blob.js';
import { loadConfig, missingSettings } from './config.js';
import { createGenerator } from './generator.js';
import { createGrokTextGenerator } from './grok.js';
import { createAzureJobQueue } from './queue.js';
import { createPostgresStore } from './store/postgres.js';
import { createUsage } from './usage.js';
import { createWorker } from './worker.js';

const config = loadConfig();
// Fastify logs method, URL and status only; the redaction is a second line of defence in case a
// header object ever ends up in a log call.
const logger = pino({
  level: config.logLevel,
  redact: ['req.headers.authorization', 'req.headers["x-app-key"]', 'req.headers["x-admin-key"]'],
});

const missing = missingSettings(config);
if (missing.length > 0) {
  logger.fatal(`Missing required settings: ${missing.join(', ')}`);
  process.exit(1);
}

const store = createPostgresStore(config.databaseUrl);
const blobs = createAzureBlobStore(config);
const queue = createAzureJobQueue(config);
const usage = createUsage({ store, config });
const textGenerator = createGrokTextGenerator({ apiKey: config.xaiApiKey, model: config.grokModel });
await Promise.all([store.init(), blobs.init(), queue.init()]);

const stoppers = [];
if (config.role !== 'worker') {
  const app = buildApp({ config, store, queue, blobs, usage, textGenerator, logger });
  await app.listen({ port: config.port, host: '0.0.0.0' });
  stoppers.push(() => app.close());
}
if (config.role !== 'api') {
  const generator = createGenerator({
    store, blobs, usage, config, textGenerator,
    speech: createAzureSpeechSynthesizer({ key: config.speechKey, region: config.speechRegion }),
  });
  const worker = createWorker({ queue, store, generator, config, logger });
  worker.start();
  logger.info('worker started');
  stoppers.push(() => worker.stop());
}

// Container Apps sends SIGTERM on scale-in and revision changes: finish what is in flight.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.once(signal, async () => {
    logger.info(`${signal} received, shutting down`);
    await Promise.allSettled(stoppers.map((stop) => stop()));
    await store.close();
    process.exit(0);
  });
}
