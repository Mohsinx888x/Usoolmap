import { setTimeout as delay } from 'node:timers/promises';
import { GenerationError } from './generator.js';

/**
 * Queue consumer. A message carries only `{jobId}`; the job row is the source of truth.
 * Delivery is at-least-once: a message that is not acknowledged reappears after the visibility
 * timeout, which is how a transient failure (network, upstream outage, crash) gets retried.
 *
 * @param {{queue: object, store: object, generator: object, config: object, logger: object, now?: () => Date}} deps
 */
export function createWorker({ queue, store, generator, config, logger, now = () => new Date() }) {
  const stopping = new AbortController();
  let loop = null;

  /** @returns {Promise<boolean>} whether a message was handled */
  async function runOnce() {
    const message = await queue.receive(config.jobVisibilityTimeoutSeconds);
    if (!message) return false;

    const job = await store.getJob(message.body.jobId);
    if (!job || job.status !== 'pending') {
      await message.ack(); // already finished by an earlier delivery, or the row is gone
      return true;
    }
    // Poison message: it keeps failing (or keeps crashing the worker). Stop paying for it.
    if (message.attempts > config.jobMaxAttempts) {
      const error = `poison: gave up after ${config.jobMaxAttempts} attempts; last error: ${job.error ?? 'unknown'}`;
      await store.updateJob(job.id, { status: 'failed', error: error.slice(0, 500), finishedAt: now() });
      await message.ack();
      logger.error({ jobId: job.id }, 'job abandoned as poison');
      return true;
    }

    await store.updateJob(job.id, { attempts: message.attempts });
    try {
      const clip = await generator.generate(job);
      await message.ack();
      logger.info({ jobId: job.id, clipId: clip.id }, 'job done');
    } catch (error) {
      if (error instanceof GenerationError) {
        await message.ack(); // final: the generator already marked the job failed
        logger.warn({ jobId: job.id, code: error.code }, 'job failed');
      } else {
        logger.warn({ jobId: job.id, attempt: message.attempts, err: error.message }, 'job will be retried');
      }
    }
    return true;
  }

  const pause = () => delay(config.workerPollSeconds * 1000, undefined, { signal: stopping.signal }).catch(() => {});

  async function run() {
    while (!stopping.signal.aborted) {
      try {
        if (!(await runOnce())) await pause();
      } catch (error) {
        logger.error({ err: error.message }, 'worker loop error');
        await pause();
      }
    }
  }

  return {
    runOnce,
    start: () => void (loop ??= run()),
    /** Lets the job in progress finish, then resolves. */
    async stop() {
      stopping.abort();
      await loop;
    },
  };
}
