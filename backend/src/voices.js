// Language -> personalities -> Azure neural voice.
//
// CAVEAT (also in the README): a regional Azure voice such as ar-QA-MoazNeural gives a regional
// ACCENT. It does not by itself produce authentic colloquial Qatari (or any other dialect)
// speech. How natural a clip sounds depends on the generated colloquial text, on its tashkeel,
// on the reviewed pronunciation dictionary and on human listening tests (scripts/voice-qa.js).
// Nothing in this backend can verify dialect authenticity automatically.

const STYLES = {
  male: 'Dry, deadpan wit: a calm friend in the passenger seat who has seen every wrong turn.',
  female: 'Bright and quick: playful teasing that is always on the driver\'s side.',
  elder: 'Unhurried and wry: an older relative with a story for everything, told slowly.',
};

// [tag, English name used in prompts and notes, [id, label, voice] male, [id, label, voice] female, Arabic dialect]
const TABLE = [
  ['ar-QA', 'Qatari Arabic', ['abu-nasser', 'أبو ناصر', 'ar-QA-MoazNeural'], ['noura', 'نورة', 'ar-QA-AmalNeural'], 'Gulf (Khaleeji) Arabic as spoken in Qatar'],
  ['ar-SA', 'Saudi Arabic', ['abu-fahad', 'أبو فهد', 'ar-SA-HamedNeural'], ['reem', 'ريم', 'ar-SA-ZariyahNeural'], 'Gulf (Khaleeji) Arabic as spoken in Saudi Arabia'],
  ['ar-AE', 'Emirati Arabic', ['bu-rashid', 'بو راشد', 'ar-AE-HamdanNeural'], ['shamma', 'شما', 'ar-AE-FatimaNeural'], 'Gulf (Khaleeji) Arabic as spoken in the United Arab Emirates'],
  ['ar-KW', 'Kuwaiti Arabic', ['bu-jassim', 'بو جاسم', 'ar-KW-FahedNeural'], ['dana', 'دانة', 'ar-KW-NouraNeural'], 'Gulf (Khaleeji) Arabic as spoken in Kuwait'],
  ['ar-BH', 'Bahraini Arabic', ['bu-ali', 'بو علي', 'ar-BH-AliNeural'], ['maryam', 'مريم', 'ar-BH-LailaNeural'], 'Gulf (Khaleeji) Arabic as spoken in Bahrain'],
  ['ar-OM', 'Omani Arabic', ['abu-said', 'أبو سعيد', 'ar-OM-AbdullahNeural'], ['zainab', 'زينب', 'ar-OM-AyshaNeural'], 'Gulf (Khaleeji) Arabic as spoken in Oman'],
  ['ar-EG', 'Egyptian Arabic', ['amm-hassan', 'عم حسن', 'ar-EG-ShakirNeural'], ['mona', 'منى', 'ar-EG-SalmaNeural'], 'Egyptian colloquial Arabic'],
  ['en-US', 'US English', ['andy', 'Andy', 'en-US-AndrewMultilingualNeural'], ['ava', 'Ava', 'en-US-AvaMultilingualNeural']],
  ['en-GB', 'British English', ['ryan', 'Ryan', 'en-GB-RyanNeural'], ['sonia', 'Sonia', 'en-GB-SoniaNeural']],
  ['en-IN', 'Indian English', ['prabhat', 'Prabhat', 'en-IN-PrabhatNeural'], ['neerja', 'Neerja', 'en-IN-NeerjaNeural']],
  ['hi-IN', 'Hindi', ['madhur', 'मधुर', 'hi-IN-MadhurNeural'], ['swara', 'स्वरा', 'hi-IN-SwaraNeural']],
  ['ur-PK', 'Urdu', ['asad', 'اسد', 'ur-PK-AsadNeural'], ['uzma', 'عظمیٰ', 'ur-PK-UzmaNeural']],
  ['fr-FR', 'French', ['henri', 'Henri', 'fr-FR-HenriNeural'], ['denise', 'Denise', 'fr-FR-DeniseNeural']],
  ['es-ES', 'Spanish', ['alvaro', 'Álvaro', 'es-ES-AlvaroNeural'], ['elvira', 'Elvira', 'es-ES-ElviraNeural']],
  ['de-DE', 'German', ['conrad', 'Conrad', 'de-DE-ConradNeural'], ['katja', 'Katja', 'de-DE-KatjaNeural']],
  ['tr-TR', 'Turkish', ['ahmet', 'Ahmet', 'tr-TR-AhmetNeural'], ['emel', 'Emel', 'tr-TR-EmelNeural']],
  ['id-ID', 'Indonesian', ['ardi', 'Ardi', 'id-ID-ArdiNeural'], ['gadis', 'Gadis', 'id-ID-GadisNeural']],
  ['ru-RU', 'Russian', ['dmitry', 'Дмитрий', 'ru-RU-DmitryNeural'], ['svetlana', 'Светлана', 'ru-RU-SvetlanaNeural']],
  ['zh-CN', 'Mandarin Chinese', ['yunxi', '云希', 'zh-CN-YunxiNeural'], ['xiaoxiao', '晓晓', 'zh-CN-XiaoxiaoNeural']],
  ['ja-JP', 'Japanese', ['keita', '圭太', 'ja-JP-KeitaNeural'], ['nanami', '七海', 'ja-JP-NanamiNeural']],
  ['pt-BR', 'Brazilian Portuguese', ['antonio', 'Antônio', 'pt-BR-AntonioNeural'], ['francisca', 'Francisca', 'pt-BR-FranciscaNeural']],
  ['fil-PH', 'Filipino', ['angelo', 'Angelo', 'fil-PH-AngeloNeural'], ['blessica', 'Blessica', 'fil-PH-BlessicaNeural']],
  ['bn-BD', 'Bangla', ['pradeep', 'প্রদীপ', 'bn-BD-PradeepNeural'], ['nabanita', 'নবনীতা', 'bn-BD-NabanitaNeural']],
];

// A third, slower personality on the same male voice: prosody is the only thing that differs.
const ELDERS = {
  'ar-QA': ['al-shayeb', 'الشايب'],
  'en-US': ['old-timer', 'Old-timer'],
};

/**
 * @typedef {{id: string, label: string, gender: 'male'|'female', voice: string, description: string, rate?: string, pitch?: string}} Personality
 * @type {Record<string, {name: string, dialect?: string, personalities: Personality[]}>}
 */
export const LANGUAGES = Object.fromEntries(
  TABLE.map(([tag, name, male, female, dialect]) => {
    const personality = ([id, label, voice], gender, style, extra = {}) => ({
      id, label, gender, voice, description: STYLES[style], ...extra,
    });
    const personalities = [personality(male, 'male', 'male'), personality(female, 'female', 'female')];
    if (ELDERS[tag]) {
      personalities.push(personality([...ELDERS[tag], male[2]], 'male', 'elder', { rate: '-8%', pitch: '-4%' }));
    }
    return [tag, { name, dialect, personalities }];
  }),
);

// Where a base language has several regions, the one used for an unlisted region.
const BASE_DEFAULT = { ar: 'ar-SA', en: 'en-US' };
const FINAL_FALLBACK = 'en-US';

const canonical = (tag) => {
  try {
    return Intl.getCanonicalLocales(tag)[0] ?? '';
  } catch {
    return '';
  }
};

/**
 * Exact tag, else the same base language, else US English. A fallback is always reported
 * honestly (`fallback: true` plus a plain note) so the app can tell the user.
 * @param {string} requestedLanguage BCP-47 tag
 */
export function resolveLanguage(requestedLanguage) {
  const tag = canonical(requestedLanguage);
  const base = tag.split('-')[0];
  const sameBase = BASE_DEFAULT[base] ?? Object.keys(LANGUAGES).find((key) => key.split('-')[0] === base);
  const language = LANGUAGES[tag] ? tag : (sameBase ?? FINAL_FALLBACK);
  const fallback = language !== tag;
  return {
    requestedLanguage,
    language,
    fallback,
    fallbackNote: fallback ? `No voice for ${requestedLanguage}; using the ${LANGUAGES[language].name} voice.` : null,
    personalities: LANGUAGES[language].personalities,
  };
}

/** @returns {Personality | undefined} */
export const findPersonality = (language, personalityId) =>
  LANGUAGES[language]?.personalities.find((p) => p.id === personalityId);
