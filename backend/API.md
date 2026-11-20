# UsoolMap voice backend API (v1)

The Android app and the backend are built against this contract. JSON everywhere except audio.

## Model

Humorous clips are short spoken **reactions to a navigation event**. They never contain the
instruction itself (direction, distance, street name): that is always spoken on the phone by the
professional voice. A clip is therefore reusable by every user with the same language,
personality and humour level, which is what makes a shared cloud library and caching possible.

- `eventType`: `route_start`, `arrival`, `reroute`, `long_straight`, `roundabout`, `u_turn`,
  `turn_done`, `traffic_delay`, `speeding`, `speed_camera`, `signal_lost`, `signal_back`,
  `stopped_long`, `night_drive`
- `language`: BCP-47 tag including the dialect region, e.g. `ar-QA`, `ar-SA`, `en-US`, `hi-IN`
- `personality`: id returned by `GET /v1/voices`
- `humourLevel`: `1` (gentle), `2` (playful), `3` (cheeky)

`Clip`:

```json
{ "id": "uuid", "eventType": "reroute", "language": "ar-QA", "personality": "abu-nasser",
  "humourLevel": 2, "text": "…", "bytes": 48213, "voice": "ar-QA-MoazNeural",
  "fallbackVoice": false, "audioUrl": "/v1/clips/<id>/audio", "createdAt": "2026-11-20T10:00:00Z" }
```

## Authentication

- `POST /v1/devices/register` needs header `X-App-Key: <app key>`.
- Every other `/v1/*` call needs `Authorization: Bearer <device token>`.
- `/v1/admin/*` needs `X-Admin-Key: <admin key>` instead.

Errors: `{ "error": { "code": "…", "message": "…" } }` with status 400, 401, 404, 429
(`rate_limited` or `quota_exceeded`, plus `retryAfterSeconds`) or 503 (`budget_exceeded`,
`generation_unavailable`).

## Endpoints

| Method and path | Body / query | Response |
| --- | --- | --- |
| `GET /healthz` | | `200 { "ok": true }` |
| `POST /v1/devices/register` | `{ "installId": "random string kept by the app" }` | `200 { "deviceId", "token" }` (same install id → same device) |
| `GET /v1/voices` | `?language=ar-QA` | `{ "requestedLanguage", "language", "fallback": bool, "fallbackNote": string or null, "personalities": [ { "id", "label", "voice", "gender", "description" } ] }` |
| `GET /v1/packs/starter` | `?language&personality&humourLevel` | `{ "clipCount", "totalBytes", "clips": [Clip] }`: a few ready clips per event type |
| `POST /v1/clips/next` | `{ "eventTypes": [..], "language", "personality", "humourLevel", "have": [clip ids already on the phone], "want": 1..20 }` | `{ "clips": [Clip], "generating": int, "quotaRemaining": int }`: ready clips this device has not been served or played within the non-repetition window; when the library runs short, generation jobs are queued |
| `POST /v1/clips/regenerate` | `{ "eventTypes": [..] (optional), "language", "personality", "humourLevel", "count": 1..10 }` | `202 { "queued": int, "quotaRemaining": int }`: always asks for brand-new clips |
| `GET /v1/clips/{id}/audio` | supports `Range` | `audio/mpeg` |
| `POST /v1/history` | `{ "plays": [ { "clipId", "playedAt": ISO-8601 } ] }` | `{ "accepted": int }` |
| `POST /v1/pronunciation/reports` | `{ "clipId" (optional), "text", "language", "note" }` | `201 { "id" }` |
| `POST /v1/admin/pregenerate` | `{ "language", "personality", "humourLevel", "perEvent": 1..20 }` | `202 { "queued": int }` |
| `GET /v1/admin/usage` | `?days=7` | per-day Grok calls, TTS characters, bytes stored, estimated cost, and the budget |
| `GET /v1/admin/pronunciation` | `?language` | dictionary entries |
| `PUT /v1/admin/pronunciation` | `{ "language", "term", "spoken" (alias) or "ipa", "reviewed": bool }` | the stored entry |
| `GET /v1/admin/pronunciation/reports` | `?status=open` | reports from users |
