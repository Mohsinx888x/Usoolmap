// Applies the reviewed pronunciation dictionary to a clip text and wraps it as Azure SSML.

import { normalizeArabic } from './arabic.js';

const escapeXml = (value) =>
  value.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[c]);

const isWordChar = (char) => char !== undefined && /[\p{L}\p{N}]/u.test(char);

/**
 * Folds text for matching (case, Arabic diacritics and spelling variants) and keeps, for each
 * folded character, the index of the original character it came from. That map is what lets a
 * dictionary term written without tashkeel match fully vowelled text, and the other way round.
 */
function fold(text) {
  let folded = '';
  const origin = [];
  for (let i = 0; i < text.length; i++) {
    for (const char of normalizeArabic(text[i]).toLowerCase()) {
      folded += char;
      origin.push(i);
    }
  }
  return { folded, origin };
}

/**
 * Whole-word matches of dictionary terms, longest term first so "سوق واقف" wins over "سوق".
 * @returns {{start: number, end: number, entry: object}[]} non-overlapping, in text order
 */
function findMatches(text, entries) {
  const { folded, origin } = fold(text);
  const terms = entries
    .map((entry) => ({ entry, term: fold(entry.term.trim().replace(/\s+/g, ' ')).folded }))
    .filter(({ term }) => term.length > 0)
    .sort((a, b) => b.term.length - a.term.length);

  const taken = new Array(folded.length).fill(false);
  const matches = [];
  for (const { entry, term } of terms) {
    for (let at = folded.indexOf(term); at !== -1; at = folded.indexOf(term, at + 1)) {
      const last = at + term.length - 1;
      const free = !taken.slice(at, last + 1).includes(true);
      if (!free || isWordChar(folded[at - 1]) || isWordChar(folded[last + 1])) continue;
      taken.fill(true, at, last + 1);
      // Up to the next folded character's origin, so marks after the last letter stay inside.
      const end = last + 1 < origin.length ? origin[last + 1] : text.length;
      matches.push({ start: origin[at], end, entry });
    }
  }
  return matches.sort((a, b) => a.start - b.start);
}

function replacement(original, entry) {
  const inner = escapeXml(original);
  return entry.ipa
    ? `<phoneme alphabet="ipa" ph="${escapeXml(entry.ipa)}">${inner}</phoneme>`
    : `<sub alias="${escapeXml(entry.spoken)}">${inner}</sub>`;
}

/**
 * @param {{text: string, language: string, voice: string, rate?: string, pitch?: string,
 *   entries?: {term: string, spoken?: string|null, ipa?: string|null, reviewed: boolean}[]}} input
 *   Entries that are not reviewed, or have neither `spoken` nor `ipa`, are ignored.
 * @returns {string} SSML document
 */
export function buildSsml({ text, language, voice, rate, pitch, entries = [] }) {
  const usable = entries.filter((entry) => entry.reviewed && (entry.spoken || entry.ipa));
  let body = '';
  let cursor = 0;
  for (const match of findMatches(text, usable)) {
    body += escapeXml(text.slice(cursor, match.start)) + replacement(text.slice(match.start, match.end), match.entry);
    cursor = match.end;
  }
  body += escapeXml(text.slice(cursor));

  if (rate || pitch) {
    const attributes = [rate && `rate="${escapeXml(rate)}"`, pitch && `pitch="${escapeXml(pitch)}"`].filter(Boolean);
    body = `<prosody ${attributes.join(' ')}>${body}</prosody>`;
  }
  return (
    `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="${escapeXml(language)}">` +
    `<voice name="${escapeXml(voice)}">${body}</voice></speak>`
  );
}
