import pg from 'pg';
import { migrate } from './migrate.js';

const toClip = (r) => ({
  id: r.id, eventType: r.event_type, language: r.language, personality: r.personality,
  humourLevel: r.humour_level, text: r.text, normalizedText: r.normalized_text, opening: r.opening,
  voice: r.voice, fallbackVoice: r.fallback_voice, blobPath: r.blob_path, bytes: r.bytes,
  status: r.status, source: r.source, createdAt: r.created_at,
});
const toJob = (r) => ({
  id: r.id, eventType: r.event_type, language: r.language, personality: r.personality,
  humourLevel: r.humour_level, requestedBy: r.requested_by, forceNew: r.force_new, status: r.status,
  attempts: r.attempts, error: r.error, createdAt: r.created_at, finishedAt: r.finished_at,
});
const toEntry = (r) => ({
  id: r.id, language: r.language, term: r.term, normalizedTerm: r.normalized_term,
  spoken: r.spoken, ipa: r.ipa, reviewed: r.reviewed, updatedAt: r.updated_at,
});
const toReport = (r) => ({
  id: r.id, deviceId: r.device_id, clipId: r.clip_id, text: r.text, language: r.language,
  note: r.note, status: r.status, createdAt: r.created_at,
});

const JOB_COLUMNS = { status: 'status', attempts: 'attempts', error: 'error', finishedAt: 'finished_at' };

/** @param {string} connectionString */
export function createPostgresStore(connectionString) {
  const pool = new pg.Pool({ connectionString, max: 10 });
  const query = (text, values) => pool.query(text, values);

  /** Runs `work(client)` in one transaction. */
  async function transaction(work) {
    const client = await pool.connect();
    try {
      await client.query('begin');
      const result = await work(client);
      await client.query('commit');
      return result;
    } catch (error) {
      await client.query('rollback');
      throw error;
    } finally {
      client.release();
    }
  }

  return {
    init: () => migrate(pool),
    close: () => pool.end(),

    async upsertDevice(id, installIdHash) {
      // The no-op update makes RETURNING yield the existing row when the install is known.
      const { rows } = await query(
        `insert into devices (id, install_id_hash) values ($1, $2)
         on conflict (install_id_hash) do update set install_id_hash = excluded.install_id_hash
         returning id`,
        [id, installIdHash],
      );
      return rows[0].id;
    },
    async deviceExists(id) {
      return (await query('select 1 from devices where id = $1', [id])).rowCount > 0;
    },

    deleteDevice(id) {
      // All or nothing. Plays would also go through the foreign key cascade; deleting them
      // explicitly keeps the erasure readable in one place.
      return transaction(async (client) => {
        await client.query('delete from plays where device_id = $1', [id]);
        await client.query('delete from device_quota where device_id = $1', [id]);
        await client.query('delete from pronunciation_reports where device_id = $1', [id]);
        await client.query('update jobs set requested_by = null where requested_by = $1', [id]);
        await client.query('delete from devices where id = $1', [id]);
      });
    },

    async insertClip(c) {
      await query(
        `insert into clips (id, event_type, language, personality, humour_level, text, normalized_text, opening,
           voice, fallback_voice, blob_path, bytes, status, source, created_at)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)`,
        [c.id, c.eventType, c.language, c.personality, c.humourLevel, c.text, c.normalizedText, c.opening,
          c.voice, c.fallbackVoice, c.blobPath, c.bytes, c.status, c.source, c.createdAt],
      );
    },
    async getClip(id) {
      const { rows } = await query('select * from clips where id = $1', [id]);
      return rows[0] ? toClip(rows[0]) : null;
    },
    async recentClips(eventType, language, limit) {
      const { rows } = await query(
        `select text, normalized_text, opening from clips where event_type = $1 and language = $2
         order by created_at desc, id limit $3`,
        [eventType, language, limit],
      );
      return rows.map((r) => ({ text: r.text, normalizedText: r.normalized_text, opening: r.opening }));
    },
    async hasClipText(eventType, language, normalizedText) {
      const { rowCount } = await query(
        'select 1 from clips where event_type = $1 and language = $2 and normalized_text = $3',
        [eventType, language, normalizedText],
      );
      return rowCount > 0;
    },
    async findClips({ eventTypes, language, personality, humourLevel, perEvent, excludeIds = [], unseenBy = null }) {
      const { rows } = await query(
        `select * from (
           select c.*, row_number() over (partition by c.event_type order by c.created_at, c.id) as rn
           from clips c
           where c.status = 'ready' and c.language = $1 and c.personality = $2 and c.humour_level = $3
             and c.event_type = any($4::text[]) and c.id <> all($5::uuid[])
             and ($6::uuid is null or not exists (
               select 1 from plays p where p.device_id = $6 and p.clip_id = c.id and p.at >= $7))
         ) ranked
         where rn <= $8
         order by array_position($4::text[], event_type), rn`,
        [language, personality, humourLevel, eventTypes, excludeIds, unseenBy?.deviceId ?? null,
          unseenBy?.since ?? null, perEvent],
      );
      return rows.map(toClip);
    },

    async recordPlays(deviceId, kind, entries) {
      // Joining on clips drops unknown ids; grouping lets one batch mention a clip twice.
      const { rowCount } = await query(
        `insert into plays (device_id, clip_id, kind, at)
         select $1, c.id, $2, max(e.at)
         from unnest($3::uuid[], $4::timestamptz[]) as e (clip_id, at) join clips c on c.id = e.clip_id
         group by c.id
         on conflict (device_id, clip_id, kind) do update set at = greatest(plays.at, excluded.at)`,
        [deviceId, kind, entries.map((e) => e.clipId), entries.map((e) => e.at)],
      );
      return rowCount;
    },

    async insertJob(j) {
      await query(
        `insert into jobs (id, event_type, language, personality, humour_level, requested_by, force_new, status, created_at)
         values ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
        [j.id, j.eventType, j.language, j.personality, j.humourLevel, j.requestedBy, j.forceNew, j.status, j.createdAt],
      );
    },
    async getJob(id) {
      const { rows } = await query('select * from jobs where id = $1', [id]);
      return rows[0] ? toJob(rows[0]) : null;
    },
    async updateJob(id, patch) {
      const fields = Object.keys(patch);
      const assignments = fields.map((field, i) => `${JOB_COLUMNS[field]} = $${i + 2}`);
      await query(`update jobs set ${assignments.join(', ')} where id = $1`, [id, ...fields.map((f) => patch[f])]);
    },
    async hasPendingJob({ eventType, language, personality, humourLevel }, since) {
      const { rowCount } = await query(
        `select 1 from jobs where status = 'pending' and language = $1 and event_type = $2
           and personality = $3 and humour_level = $4 and created_at >= $5 limit 1`,
        [language, eventType, personality, humourLevel, since],
      );
      return rowCount > 0;
    },

    consumeQuota(deviceId, day, wanted, limit) {
      // Row lock, so two concurrent requests from one device cannot both take the last units.
      return transaction(async (client) => {
        await client.query('insert into device_quota (device_id, day) values ($1, $2) on conflict do nothing', [deviceId, day]);
        const { rows } = await client.query('select used from device_quota where device_id = $1 and day = $2 for update', [deviceId, day]);
        const granted = Math.max(0, Math.min(wanted, limit - rows[0].used));
        await client.query('update device_quota set used = used + $3 where device_id = $1 and day = $2', [deviceId, day, granted]);
        return granted;
      });
    },
    async getQuotaUsed(deviceId, day) {
      const { rows } = await query('select used from device_quota where device_id = $1 and day = $2', [deviceId, day]);
      return rows[0]?.used ?? 0;
    },

    async addUsage(day, { grokCalls, ttsCharacters, bytesStored }) {
      await query(
        `insert into usage_daily (day, grok_calls, tts_characters, bytes_stored) values ($1, $2, $3, $4)
         on conflict (day) do update set
           grok_calls = usage_daily.grok_calls + excluded.grok_calls,
           tts_characters = usage_daily.tts_characters + excluded.tts_characters,
           bytes_stored = usage_daily.bytes_stored + excluded.bytes_stored`,
        [day, grokCalls, ttsCharacters, bytesStored],
      );
    },
    async getUsage(sinceDay) {
      // to_char keeps the day a plain string (a JS Date would shift it by the server time zone);
      // bigint columns arrive as strings and stay far below 2^53.
      const { rows } = await query(
        `select to_char(day, 'YYYY-MM-DD') as day, grok_calls, tts_characters, bytes_stored
         from usage_daily where day >= $1 order by day desc`,
        [sinceDay],
      );
      return rows.map((r) => ({
        day: r.day, grokCalls: r.grok_calls, ttsCharacters: Number(r.tts_characters), bytesStored: Number(r.bytes_stored),
      }));
    },

    async listPronunciation(languages = null, reviewedOnly = false) {
      const { rows } = await query(
        `select * from pronunciation
         where ($1::text[] is null or language = any($1)) and (not $2 or reviewed) order by id`,
        [languages, reviewedOnly],
      );
      return rows.map(toEntry);
    },
    async upsertPronunciation({ language, term, normalizedTerm, spoken, ipa, reviewed, updatedAt }) {
      const { rows } = await query(
        `insert into pronunciation (language, term, normalized_term, spoken, ipa, reviewed, updated_at)
         values ($1, $2, $3, $4, $5, $6, $7)
         on conflict (language, normalized_term) do update set
           term = excluded.term, spoken = excluded.spoken, ipa = excluded.ipa,
           reviewed = excluded.reviewed, updated_at = excluded.updated_at
         returning *`,
        [language, term, normalizedTerm, spoken, ipa, reviewed, updatedAt],
      );
      return toEntry(rows[0]);
    },

    async insertReport(r) {
      await query(
        `insert into pronunciation_reports (id, device_id, clip_id, text, language, note, created_at)
         values ($1, $2, $3, $4, $5, $6, $7)`,
        [r.id, r.deviceId, r.clipId, r.text, r.language, r.note, r.createdAt],
      );
    },
    async listReports(status = null) {
      const { rows } = await query(
        'select * from pronunciation_reports where ($1::text is null or status = $1) order by created_at desc',
        [status],
      );
      return rows.map(toReport);
    },
  };
}
