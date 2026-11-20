import { withRetries } from './http.js';

const ENDPOINT = 'https://api.x.ai/v1/chat/completions';

/**
 * TextGenerator backed by the xAI Grok chat completions API.
 * @param {{apiKey: string, model: string, fetch?: typeof fetch, sleep?: (ms: number) => Promise<void>, timeoutMs?: number}} options
 * @returns {{generate(prompt: {system: string, user: string}): Promise<string>}}
 */
export function createGrokTextGenerator({ apiKey, model, fetch = globalThis.fetch, sleep, timeoutMs = 30_000 }) {
  return {
    async generate({ system, user }) {
      const response = await withRetries(
        () =>
          fetch(ENDPOINT, {
            method: 'POST',
            headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
            body: JSON.stringify({
              model,
              messages: [
                { role: 'system', content: system },
                { role: 'user', content: user },
              ],
              temperature: 1,
              max_tokens: 400,
            }),
            signal: AbortSignal.timeout(timeoutMs),
          }),
        { service: 'Grok', sleep },
      );
      const data = await response.json();
      return data?.choices?.[0]?.message?.content ?? '';
    },
  };
}
