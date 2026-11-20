// Duplicate detection for jokes. This is LEXICAL similarity (shared words and character
// sequences). It is not embedding-based semantic similarity: two jokes with the same idea told
// in entirely different words will not be caught here. The prompt's "differ from these recent
// jokes" list is the only defence against that case.

import { normalizeArabic } from './arabic.js';

/** Lower-cased, punctuation-free, single-spaced text with Arabic spelling variants folded. */
export function normalize(text) {
  return normalizeArabic(text.normalize('NFKC'))
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** First two normalised words: what makes consecutive clips sound the same to a listener. */
export const openingPhrase = (text) => normalize(text).split(' ').slice(0, 2).join(' ');

/** Word bigrams plus character trigrams of already-normalised text. */
export function shingles(normalized) {
  const set = new Set();
  const words = normalized.split(' ').filter(Boolean);
  for (let i = 0; i + 1 < words.length; i++) set.add(`w:${words[i]} ${words[i + 1]}`);
  for (let i = 0; i + 3 <= normalized.length; i++) set.add(`c:${normalized.slice(i, i + 3)}`);
  return set;
}

export function jaccard(a, b) {
  if (a.size === 0 && b.size === 0) return 1;
  let shared = 0;
  for (const item of a) if (b.has(item)) shared++;
  return shared / (a.size + b.size - shared);
}

/** Similarity in [0, 1] of two already-normalised texts. */
export const similarity = (normalizedA, normalizedB) => jaccard(shingles(normalizedA), shingles(normalizedB));
