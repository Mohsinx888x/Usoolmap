// In-memory Store with the same contract as store/postgres.js (test/store.test.js runs one
// suite against both). Used by the tests and by scripts/voice-qa.js.

const jobKey = (j) => [j.eventType, j.language, j.personality, j.humourLevel].join('|');

export function createMemoryStore() {
  const devices = new Map(); // installIdHash -> id
  const clips = [];
  const plays = new Map(); // `${deviceId}|${clipId}|${kind}` -> Date
  const jobs = new Map();
  const usage = new Map(); // day -> counters
  const quota = new Map(); // `${deviceId}|${day}` -> used
  const pronunciation = new Map(); // `${language}|${normalizedTerm}` -> entry
  const reports = [];
  let pronunciationId = 0;

  const seenSince = (deviceId, clipId, since) =>
    ['served', 'played'].some((kind) => plays.get(`${deviceId}|${clipId}|${kind}`) >= since);

  return {
    init: async () => {},
    close: async () => {},

    async upsertDevice(id, installIdHash) {
      if (!devices.has(installIdHash)) devices.set(installIdHash, id);
      return devices.get(installIdHash);
    },
    deviceExists: async (id) => [...devices.values()].includes(id),
    async deleteDevice(id) {
      for (const [hash, deviceId] of devices) if (deviceId === id) devices.delete(hash);
      for (const key of [...plays.keys(), ...quota.keys()].filter((k) => k.startsWith(`${id}|`))) {
        plays.delete(key);
        quota.delete(key);
      }
      for (const job of jobs.values()) if (job.requestedBy === id) job.requestedBy = null;
      for (let i = reports.length - 1; i >= 0; i--) if (reports[i].deviceId === id) reports.splice(i, 1);
    },

    async insertClip(clip) {
      const duplicate = clips.some(
        (c) => c.language === clip.language && c.eventType === clip.eventType && c.normalizedText === clip.normalizedText,
      );
      if (duplicate) throw new Error('duplicate clip text for this language and event type');
      clips.push({ ...clip });
    },
    getClip: async (id) => clips.find((c) => c.id === id) ?? null,
    async recentClips(eventType, language, limit) {
      return clips
        .filter((c) => c.eventType === eventType && c.language === language)
        .sort((a, b) => b.createdAt - a.createdAt)
        .slice(0, limit)
        .map(({ text, normalizedText, opening }) => ({ text, normalizedText, opening }));
    },
    hasClipText: async (eventType, language, normalizedText) =>
      clips.some((c) => c.eventType === eventType && c.language === language && c.normalizedText === normalizedText),
    async findClips({ eventTypes, language, personality, humourLevel, perEvent, excludeIds = [], unseenBy = null }) {
      return eventTypes.flatMap((eventType) =>
        clips
          .filter(
            (c) =>
              c.status === 'ready' && c.eventType === eventType && c.language === language &&
              c.personality === personality && c.humourLevel === humourLevel && !excludeIds.includes(c.id) &&
              !(unseenBy && seenSince(unseenBy.deviceId, c.id, unseenBy.since)),
          )
          .sort((a, b) => a.createdAt - b.createdAt)
          .slice(0, perEvent),
      );
    },

    async recordPlays(deviceId, kind, entries) {
      const known = entries.filter((entry) => clips.some((c) => c.id === entry.clipId));
      for (const { clipId, at } of known) {
        const key = `${deviceId}|${clipId}|${kind}`;
        const previous = plays.get(key);
        plays.set(key, previous > at ? previous : at);
      }
      return new Set(known.map((entry) => entry.clipId)).size;
    },

    insertJob: async (job) => void jobs.set(job.id, { attempts: 0, error: null, finishedAt: null, ...job }),
    getJob: async (id) => jobs.get(id) ?? null,
    updateJob: async (id, patch) => void Object.assign(jobs.get(id) ?? {}, patch),
    hasPendingJob: async (key, since) =>
      [...jobs.values()].some((j) => j.status === 'pending' && j.createdAt >= since && jobKey(j) === jobKey(key)),

    async consumeQuota(deviceId, day, wanted, limit) {
      const key = `${deviceId}|${day}`;
      const used = quota.get(key) ?? 0;
      const granted = Math.max(0, Math.min(wanted, limit - used));
      quota.set(key, used + granted);
      return granted;
    },
    getQuotaUsed: async (deviceId, day) => quota.get(`${deviceId}|${day}`) ?? 0,

    async addUsage(day, { grokCalls, ttsCharacters, bytesStored }) {
      const row = usage.get(day) ?? { day, grokCalls: 0, ttsCharacters: 0, bytesStored: 0 };
      usage.set(day, {
        day,
        grokCalls: row.grokCalls + grokCalls,
        ttsCharacters: row.ttsCharacters + ttsCharacters,
        bytesStored: row.bytesStored + bytesStored,
      });
    },
    getUsage: async (sinceDay) =>
      [...usage.values()].filter((row) => row.day >= sinceDay).sort((a, b) => (a.day < b.day ? 1 : -1)),

    async listPronunciation(languages = null, reviewedOnly = false) {
      return [...pronunciation.values()]
        .filter((e) => (!languages || languages.includes(e.language)) && (!reviewedOnly || e.reviewed))
        .sort((a, b) => a.id - b.id);
    },
    async upsertPronunciation({ language, term, normalizedTerm, spoken, ipa, reviewed, updatedAt }) {
      const key = `${language}|${normalizedTerm}`;
      const id = pronunciation.get(key)?.id ?? ++pronunciationId;
      const entry = { id, language, term, normalizedTerm, spoken, ipa, reviewed, updatedAt };
      pronunciation.set(key, entry);
      return entry;
    },

    insertReport: async (report) => void reports.push({ status: 'open', ...report }),
    listReports: async (status = null) =>
      reports.filter((r) => !status || r.status === status).sort((a, b) => b.createdAt - a.createdAt),
  };
}
