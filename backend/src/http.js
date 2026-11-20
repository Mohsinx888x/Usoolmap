// Shared retry policy for the two outbound HTTP services.

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export class UpstreamError extends Error {
  /** @param {string} service @param {number} status @param {string} detail */
  constructor(service, status, detail) {
    super(`${service} responded ${status}: ${detail}`);
    this.name = 'UpstreamError';
    this.status = status;
  }
}

/**
 * Calls `send()` until it returns a response that is not 429/5xx, with exponential backoff and
 * jitter. Other 4xx responses are a problem with our request, so retrying would only burn money.
 * @param {() => Promise<Response>} send
 * @param {{service: string, attempts?: number, baseDelayMs?: number, sleep?: (ms: number) => Promise<void>}} options
 */
export async function withRetries(send, { service, attempts = 4, baseDelayMs = 500, sleep = sleepMs }) {
  for (let attempt = 1; ; attempt++) {
    let failure;
    try {
      const response = await send();
      if (response.ok) return response;
      failure = new UpstreamError(service, response.status, (await response.text()).slice(0, 300));
    } catch (error) {
      failure = error; // network error or timeout
    }
    const retryable = !(failure instanceof UpstreamError) || failure.status === 429 || failure.status >= 500;
    if (!retryable || attempt >= attempts) throw failure;
    await sleep(baseDelayMs * 2 ** (attempt - 1) * (0.5 + Math.random() / 2));
  }
}
