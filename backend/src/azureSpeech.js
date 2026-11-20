import { withRetries } from './http.js';

/**
 * SpeechSynthesizer backed by the Azure AI Speech REST API.
 * @param {{key: string, region: string, fetch?: typeof fetch, sleep?: (ms: number) => Promise<void>, timeoutMs?: number}} options
 * @returns {{synthesize(ssml: string): Promise<Buffer>}} mp3 bytes
 */
export function createAzureSpeechSynthesizer({ key, region, fetch = globalThis.fetch, sleep, timeoutMs = 30_000 }) {
  const endpoint = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
  return {
    async synthesize(ssml) {
      const response = await withRetries(
        () =>
          fetch(endpoint, {
            method: 'POST',
            headers: {
              'Ocp-Apim-Subscription-Key': key,
              'Content-Type': 'application/ssml+xml',
              'X-Microsoft-OutputFormat': 'audio-24khz-48kbitrate-mono-mp3',
              'User-Agent': 'usoolmap-voice-backend',
            },
            body: ssml,
            signal: AbortSignal.timeout(timeoutMs),
          }),
        { service: 'Azure Speech', sleep },
      );
      const audio = Buffer.from(await response.arrayBuffer());
      if (audio.length === 0) throw new Error('Azure Speech returned an empty audio body');
      return audio;
    },
  };
}
