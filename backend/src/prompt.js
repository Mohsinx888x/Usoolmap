import { isArabic } from './arabic.js';
import { EVENTS } from './events.js';
import { LANGUAGES, findPersonality } from './voices.js';

const HUMOUR = {
  1: 'gentle: a warm smile, nothing sharp',
  2: 'playful: clearly a joke, light teasing allowed',
  3: 'cheeky: bolder teasing and exaggeration, still kind and never rude',
};

const SYSTEM = `You write lines for the humorous voice of a car navigation app. Each line is spoken aloud to a driver as a short reaction to something that just happened on the road. The actual navigation instruction is spoken separately by another voice.

Rules for every line:
- Exactly one short spoken sentence, at most about 22 words, easy to say aloud.
- An ORIGINAL joke or remark about the driving situation, written natively in the requested language and dialect. It must be culturally natural there; it must NOT be a translation of an English joke.
- Family-friendly. Nothing about religion, politics, nationality, gender or stereotypes of any group.
- Never encourage speeding, phone use, or any unsafe or illegal driving.
- It must NOT contain directions, distances, times, numbers with units, or street and place names.
- No emoji, no quotation marks, no stage directions, no explanation.
Reply with the sentence only.`;

const list = (items) => items.map((item) => `- ${item}`).join('\n');

/**
 * @param {{eventType: string, language: string, personality: string, humourLevel: number,
 *   recentTexts?: string[], recentOpenings?: string[], rejected?: {text: string, reason: string}[]}} input
 * @returns {{system: string, user: string}}
 */
export function buildPrompt({ eventType, language, personality, humourLevel, recentTexts = [], recentOpenings = [], rejected = [] }) {
  const event = EVENTS[eventType];
  const { name, dialect } = LANGUAGES[language];
  const voice = findPersonality(language, personality);

  const parts = [
    `Language: ${name} (${language}).`,
    `Situation: ${event.situation}`,
    `Speaker personality: ${voice.label}. ${voice.description}`,
  ];
  parts.push(
    event.humour
      ? `Humour level: ${HUMOUR[humourLevel]}.`
      : `Tone: this is not a moment for a punchline. Write a warm, light remark at most as strong as "${HUMOUR[1]}".`,
  );
  if (event.care) parts.push(`Take care: ${event.care}`);

  if (isArabic(language)) {
    const gulf = dialect.startsWith('Gulf');
    parts.push(
      `Dialect: write in ${dialect}, the everyday colloquial speech people use with friends${gulf ? ' in the Gulf' : ''}. Do NOT write Modern Standard Arabic (fusha).`,
      'Tashkeel: every word must be fully vowelled with tashkeel (harakat, shadda, sukun) so a speech engine pronounces the dialect correctly.',
      'Write any number as words, never as digits.',
    );
  }

  if (recentOpenings.length > 0) {
    parts.push(`Do NOT begin with any of these openings (already used):\n${list(recentOpenings)}`);
  }
  if (recentTexts.length > 0) {
    parts.push(`Your line must be clearly different, in idea and in wording, from these recent ones:\n${list(recentTexts)}`);
  }
  if (rejected.length > 0) {
    parts.push(
      `Your previous attempts were rejected. Write something different:\n${list(rejected.map((r) => `"${r.text}" (rejected: ${r.reason})`))}`,
    );
  }
  return { system: SYSTEM, user: parts.join('\n\n') };
}
