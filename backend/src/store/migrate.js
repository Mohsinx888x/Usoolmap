import { readFile } from 'node:fs/promises';

const SCHEMA_URL = new URL('./schema.sql', import.meta.url);
// Arbitrary constant: replicas starting together take this lock so only one applies the schema
// at a time (concurrent CREATE TABLE IF NOT EXISTS can otherwise fail on a catalog conflict).
const MIGRATION_LOCK = 7_415_062;

/** Applies schema.sql. Safe to run at every start because the schema is idempotent. */
export async function migrate(pool) {
  const sql = await readFile(SCHEMA_URL, 'utf8');
  const client = await pool.connect();
  try {
    await client.query('select pg_advisory_lock($1)', [MIGRATION_LOCK]);
    try {
      await client.query(sql);
    } finally {
      await client.query('select pg_advisory_unlock($1)', [MIGRATION_LOCK]);
    }
  } finally {
    client.release();
  }
}
