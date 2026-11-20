# UsoolMap voice backend

Cloud service behind the humorous voice of the UsoolMap navigation app. It writes short spoken
reactions to navigation events with the xAI Grok API, turns them into audio with Azure AI Speech,
keeps the audio in Azure Blob Storage and the metadata in PostgreSQL, and serves the clips to
phones, which cache them. A clip never contains the navigation instruction itself.

The HTTP contract is [`API.md`](API.md). Deployment is in [`../infra`](../infra/README.md).

## How it runs

One container, two roles (`ROLE=api`, `ROLE=worker`, or `ROLE=all`, the default):

- **API** (`src/app.js`, Fastify): registration, voices, clip delivery, history, reports, admin.
  It never generates clips itself: when the library is short it puts a job on the queue. Its only
  direct model call is the assistant command (`src/assistant.js`), which needs `XAI_API_KEY`.
- **Worker** (`src/worker.js`): takes jobs from the Azure Storage Queue and runs the pipeline in
  `src/generator.js`.

All settings are environment variables (`src/config.js`, `.env.example`). In Azure the secret ones
are Key Vault references resolved by Container Apps, so the code never talks to Key Vault.

| File | Purpose |
| --- | --- |
| `src/server.js` | Entry point: wires the real services, starts API and/or worker, graceful shutdown |
| `src/app.js` | `buildApp(deps)`: every route, validation, authentication, error format |
| `src/config.js` | Environment parsing with defaults; required settings per role |
| `src/auth.js` | HMAC-SHA256 device tokens, install-id hashing, constant-time comparison |
| `src/ratelimit.js` | Per-device token bucket (in memory, per replica) |
| `src/voices.js` | Language → personalities → Azure voice, and the fallback rules |
| `src/events.js` | The 14 event types: situation text for the prompt, and whether a joke fits |
| `src/prompt.js` | Builds the system and user prompt |
| `src/similarity.js`, `src/arabic.js` | Normalisation, opening phrase, shingle Jaccard, tashkeel ratio |
| `src/pronunciation.js` | Applies the reviewed dictionary and builds SSML |
| `src/grok.js`, `src/azureSpeech.js`, `src/http.js` | Outbound clients with retry and backoff on 429/5xx |
| `src/blob.js`, `src/queue.js`, `src/azureClients.js` | Azure Storage implementations and in-memory fakes |
| `src/store/` | `postgres.js`, `memory.js`, `schema.sql`, `migrate.js` (applies the schema at every start) |
| `src/generator.js` | The generation pipeline |
| `src/assistant.js` | Assistant command: action catalogue, prompt, server-side validation of the model's reply |
| `src/usage.js` | Usage counters, cost estimate, daily budget |
| `scripts/voice-qa.js` | Sample clips and a review sheet for a human listener |

Every external dependency sits behind a small interface with an in-memory fake (`TextGenerator`,
`SpeechSynthesizer`, `BlobStore`, `JobQueue`, `Store`), so the whole pipeline is tested offline.

## Generation pipeline

For a job `{eventType, language, personality, humourLevel, requestedBy, forceNew}`:

1. **Budget.** If today's estimated spend has reached `DAILY_BUDGET_USD` the job fails with
   `budget_exceeded` before anything is called.
2. **Reuse.** A job that is not `forceNew` and was caused by a phone is skipped when the library
   already holds a clip that phone has not heard (another job may have filled it meanwhile).
3. **Prompt.** One short sentence, an original joke about the situation, native to the language,
   in the personality's style at the humour level, family-friendly, no directions, distances or
   street names, with the recent openings and jokes to avoid. Arabic languages get: colloquial
   dialect (Gulf/Khaleeji for `ar-QA`, `ar-AE`, `ar-SA`, `ar-KW`, `ar-BH`, `ar-OM`), explicitly not
   Modern Standard Arabic, fully vowelled, numbers as words.
4. **Validate.** Not empty; at most 300 characters (480 for Arabic); no digits with a unit;
   Arabic: no digits at all and a tashkeel ratio of at least 0.4.
5. **Dedupe** against clips of the same event type and language: same normalised text, same
   opening (first two words) as one of the last 15, or shingle similarity at or above
   `SIMILARITY_THRESHOLD`. A rejected text is sent back to the model with the reason; three
   attempts in total, then the job fails with `no_acceptable_text`.
6. **SSML** with the reviewed pronunciation dictionary, **speech**, **blob upload**
   (`clips/{language}/{eventType}/{id}.mp3`), **clip row**, **usage counters**.

### What the dedupe is and is not

It is **lexical**: Jaccard similarity over word bigrams and character trigrams of the normalised
text (case, punctuation, Arabic diacritics and tatweel removed; alef, ya and ta marbuta forms
unified). It is **not** embedding-based semantic similarity. The same idea told in different words
passes these checks; only the "be different from these recent jokes" list in the prompt works
against that, and nothing verifies that the model obeyed. The similarity comparison covers the
300 most recent clips of the event type and language (the exact-text check covers all of them).

## Voices and dialects: what to expect

`src/voices.js` maps each supported language to two or three personalities with an Azure neural
voice. An unsupported region falls back to the same base language (`ar-JO` → `ar-SA`,
`es-MX` → `es-ES`), otherwise to `en-US`, always with `fallback: true` and a plain note.

**A regional Azure voice gives a regional accent. It does not by itself produce authentic
colloquial Qatari speech** (the same holds for every other dialect). How natural a clip sounds
depends on the generated colloquial text, on its tashkeel, on the reviewed pronunciation
dictionary, and on people listening to the result. This backend cannot check dialect quality
automatically, and no one has listened to any output of it yet.

The listening test is `scripts/voice-qa.js`:

```sh
XAI_API_KEY=... AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=... \
  node scripts/voice-qa.js --language ar-QA --personality abu-nasser --humour 2 --out ./qa-out
```

It generates one clip per event type with the real services into `./qa-out`, plus
`review-<language>-<personality>.csv` with empty columns for a native speaker (pronunciation ok,
sounds like the dialect, mispronounced words, notes). Corrections go into the dictionary with
`PUT /v1/admin/pronunciation`; only entries with `reviewed: true` are ever applied. The ten Qatar
place names seeded by `schema.sql` are deliberately **unreviewed** first guesses and stay unused
until someone reviews them. Matching is whole-word, so a name with an attached prefix
(for example `بالدوحة`) needs its own entry.

## Cost control

- `DAILY_DEVICE_QUOTA` (default 20): generation jobs one device can cause per UTC day.
- `MAX_JOBS_PER_REQUEST` (default 3) and no second job while one is pending for the same
  combination (a pending job older than `PENDING_JOB_TTL_MINUTES` stops counting).
- `DAILY_BUDGET_USD`: when today's **estimated** spend reaches it, nothing is generated until
  00:00 UTC. `GET /v1/admin/usage` shows the counters and the estimate.

The estimate is `grokCalls × PRICE_GROK_PER_CALL_USD + ttsCharacters / 1,000,000 ×
PRICE_TTS_PER_MILLION_CHARS_USD`. **The defaults (0.002 and 16) are placeholders, not quoted
prices: set both from your own xAI and Azure price sheets.** The estimate counts clip text
characters (Azure also bills most SSML markup, so it runs slightly low) and ignores storage,
database, compute and egress; the Azure budget alert in `infra/` covers those.

## Running locally

Needs Node.js 22, PostgreSQL, and either a storage account or the Azurite emulator.

```sh
npm ci
cp .env.example .env     # fill in; AZURE_STORAGE_CONNECTION_STRING=UseDevelopmentStorage=true for Azurite
npx azurite --inMemoryPersistence --silent &
node --env-file=.env src/server.js
```

The schema is created at start. `ROLE=api` runs without the Speech key (it still needs `XAI_API_KEY`
for the assistant command).

## Tests

```sh
npm test
```

runs everything with the built-in `node:test` runner. No test calls Grok or Azure.

- `test/unit.test.js`: similarity, Arabic, SSML, voices, tokens, rate limiter, prompt, clients
  (against a fake `fetch`).
- `test/assistant.test.js`: action validator, prompt, and the assistant endpoint, with fakes.
- `test/pipeline.test.js`: generator, worker and the QA script, with fakes.
- `test/api.test.js`: every endpoint through `app.inject`, with fakes.
- `test/store.test.js`: one Store contract suite against the in-memory store and, when
  `TEST_DATABASE_URL` is set, against a real PostgreSQL server. It works in a schema it creates
  and drops again, so the database is left as it was. Without the variable that part is reported
  as skipped.
- `test/azurite.test.js`: `blob.js` and `queue.js` against Azurite, which the test starts itself
  on `127.0.0.1:10000/10001`. If the emulator cannot start, the suite is reported as skipped.

Commands used to run the PostgreSQL part on the development machine (PostgreSQL 16 from
`/usr/lib/postgresql/16/bin`, started as the `postgres` user because `initdb` refuses to run as root):

```sh
PGDIR=$(mktemp -d /tmp/usoolmap-pg.XXXXXX) && chown postgres "$PGDIR"
runuser -u postgres -- /usr/lib/postgresql/16/bin/initdb -D "$PGDIR/data" -A trust -U postgres
runuser -u postgres -- /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDIR/data" -l "$PGDIR/log" -w \
  -o "-p 54329 -c listen_addresses=127.0.0.1 -c unix_socket_directories=$PGDIR" start
runuser -u postgres -- /usr/lib/postgresql/16/bin/createdb -h 127.0.0.1 -p 54329 usoolmap_test

TEST_DATABASE_URL=postgres://postgres@127.0.0.1:54329/usoolmap_test npm test

runuser -u postgres -- /usr/lib/postgresql/16/bin/pg_ctl -D "$PGDIR/data" stop && rm -rf "$PGDIR"
```

## Assistant commands and device deletion

- **`POST /v1/assistant/command`** `{text, language}` asks the text model to pick exactly one
  action from the fixed catalogue in `src/assistant.js`. The reply is parsed and validated on the
  server (known action, required parameters present, right types, enum values, real booleans,
  one-line `query` of 2 to 120 characters) and the response is rebuilt from the validated values:
  `{ "action": { "action": "navigate_to", "query": "…" } }`, or `{ "action": null }` for "none",
  non-JSON, an unknown action or a bad parameter. Raw model text is never returned. Each call
  counts as one Grok call in usage, is refused with `503 budget_exceeded` once the daily budget is
  spent, and shares the per-device rate limiter. It does not use the per-device generation quota.
  Validation guarantees a well-formed action; it cannot guarantee the model chose the *right* one,
  so the app should confirm anything consequential with the user.
- **`POST /v1/me/delete`** erases the calling device: its `plays`, `device_quota` and
  `pronunciation_reports` rows are **deleted** (reports are removed, not anonymised, so a user's
  free-text notes do not outlive the device), `jobs.requested_by` is set to null, and the
  `devices` row is removed, in one transaction. The token stops working immediately (every
  device request checks that the device still exists). Registering again with the same install id
  creates a new device with no history. Shared clips and usage counters are not personal data and
  stay; request logs in Log Analytics age out with its retention (30 days in `infra/`).

## Decisions where `API.md` leaves room

- **`want` in `POST /v1/clips/next`** is the total for the response. The requested event types
  take turns, so each gets a fair share; an event type is "short" when it could not supply
  `ceil(want / eventTypes.length)` clips.
- **`generating`** counts the jobs queued by this request. It is 0 when an identical job is
  already pending, when the device quota is used up, or when the daily budget is spent.
- **`have`** only excludes clips from the response; it does not mark them as served.
- **Errors**: `retryAfterSeconds` is inside the `error` object, and also sent as `Retry-After`.
  A range that starts beyond the end of a clip gets `416`, a status `API.md` does not list.
- **Fallback languages**: clips are stored under the resolved language (`ar-JO` uses the `ar-SA`
  library) and are returned with `fallbackVoice: true` and `language: "ar-SA"`.
- **`POST /v1/admin/pregenerate`** queues `perEvent` new clips for each of the 14 event types
  every time it is called, and needs a language that has its own voice.
- **`POST /v1/clips/regenerate`** queues as many jobs as the quota still allows (`queued` may be
  less than `count`); `429 quota_exceeded` only when none is left. `503 budget_exceeded` when the
  daily budget is spent, `503 generation_unavailable` when the queue cannot be reached.
- **`POST /v1/history`** processes the first 200 entries of a call and ignores the rest; play
  times in the future are clamped to now.
- **Admin lists** are wrapped: `{ "entries": [...] }`, `{ "reports": [...] }`; usage is
  `{ "dailyBudgetUsd", "days": [{ "date", "grokCalls", "ttsCharacters", "bytesStored", "estimatedCostUsd" }] }`.
- **`<mstts:express-as>`** is not emitted: no personality defines a speaking style. A personality
  with `rate`/`pitch` gets a `<prosody>` element.
- All days (quota, usage, budget) are UTC days.

## Known limits

- The rate limiter is per replica; with N replicas a device gets N times the burst.
- Device quota is taken when a job is queued and is not given back if the job later fails.
- A device token does not expire; rotating `TOKEN_SECRET` invalidates all of them (the app then
  registers again and keeps its device id, because the id is tied to the install id).
- Nothing deletes old clips, blobs, plays or job rows.

## Not verified

Stated plainly, because none of this could be done from the development machine:

- **No real Grok call was made.** The request shape follows the public chat-completions format;
  `GROK_MODEL` defaults to `latest` as specified, and whether xAI accepts that as a model name was
  not checked. Set it to a model id your account lists if it does not.
- **No real Azure Speech call was made.** The voice names in `src/voices.js` were not checked
  against the live voice list of any region, and no audio was ever produced or listened to.
  Joke quality, dialect authenticity and tashkeel quality of real model output are unknown.
- **Azure Storage was only tested against the Azurite emulator** with the development connection
  string. The managed-identity path (`DefaultAzureCredential` + `AZURE_STORAGE_ACCOUNT`) was not run.
- **PostgreSQL was tested against a local PostgreSQL 16**, not Azure Database for PostgreSQL;
  the TLS connection (`sslmode=require`) was not exercised.
- **The Docker image was not built** (no Docker daemon available), and nothing was deployed.
- **`infra/main.bicep` was not compiled or deployed** (see `infra/README.md`).
- Load, concurrency beyond the quota test, and long-running behaviour were not tested.
