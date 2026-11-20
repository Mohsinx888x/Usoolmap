/**
 * Per-key token bucket held in memory. With several replicas each one has its own buckets, so
 * the effective limit is (replicas x limit); it protects against a runaway client, it is not a
 * billing control (the daily device quota and the daily budget are, and they live in the database).
 *
 * @param {{capacity: number, refillPerSecond: number, now?: () => number, maxKeys?: number}} options
 */
export function createRateLimiter({ capacity, refillPerSecond, now = Date.now, maxKeys = 50_000 }) {
  /** @type {Map<string, {tokens: number, at: number}>} */
  const buckets = new Map();
  const refill = (bucket, time) =>
    Math.min(capacity, bucket.tokens + ((time - bucket.at) / 1000) * refillPerSecond);

  // Full buckets carry no information, so dropping them keeps memory bounded.
  const prune = (time) => {
    for (const [key, bucket] of buckets) if (refill(bucket, time) >= capacity) buckets.delete(key);
  };

  return {
    /** @returns {{ok: boolean, retryAfterSeconds: number}} */
    take(key) {
      const time = now();
      if (buckets.size >= maxKeys) prune(time);
      const bucket = buckets.get(key) ?? { tokens: capacity, at: time };
      const tokens = refill(bucket, time);
      const ok = tokens >= 1;
      buckets.set(key, { tokens: ok ? tokens - 1 : tokens, at: time });
      return { ok, retryAfterSeconds: ok ? 0 : Math.max(1, Math.ceil((1 - tokens) / refillPerSecond)) };
    },
    size: () => buckets.size,
  };
}
