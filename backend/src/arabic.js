// Arabic text helpers shared by similarity, validation and pronunciation matching.

// Harakat, tanwin, shadda, sukun (U+064B-U+0652), dagger alef (U+0670) and tatweel (U+0640).
const DIACRITICS_AND_TATWEEL = /[ً-ْٰـ]/g;
const TASHKEEL = /[ً-ْ]/g;
const ARABIC_LETTERS = /[ء-غف-يٱ-ۓ]/g;

export const isArabic = (language) => /^ar(-|$)/i.test(language);

export const stripDiacritics = (text) => text.replace(DIACRITICS_AND_TATWEEL, '');

/** Diacritics removed and the spelling variants people mix freely folded to one form. */
export function normalizeArabic(text) {
  return stripDiacritics(text)
    .replace(/[آأإٱ]/g, 'ا') // alef with madda / hamza / wasla -> alef
    .replace(/ى/g, 'ي') // alef maqsura -> ya
    .replace(/ة/g, 'ه'); // ta marbuta -> ha
}

/** Tashkeel marks (U+064B-U+0652) divided by Arabic letters; 0 when there are no letters. */
export function tashkeelRatio(text) {
  const letters = text.match(ARABIC_LETTERS)?.length ?? 0;
  return letters === 0 ? 0 : (text.match(TASHKEEL)?.length ?? 0) / letters;
}
