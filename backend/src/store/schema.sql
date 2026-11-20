-- UsoolMap voice backend schema. Applied at every start by store/migrate.js, so every statement
-- here must be idempotent (IF NOT EXISTS / ON CONFLICT DO NOTHING).

create table if not exists devices (
  id uuid primary key,
  install_id_hash text not null unique,
  created_at timestamptz not null default now()
);

create table if not exists clips (
  id uuid primary key,
  event_type text not null,
  language text not null,
  personality text not null,
  humour_level smallint not null check (humour_level between 1 and 3),
  text text not null,
  normalized_text text not null,
  opening text not null,
  voice text not null,
  fallback_voice boolean not null default false,
  blob_path text not null,
  bytes integer not null,
  status text not null default 'ready',
  source text not null default 'generated',
  created_at timestamptz not null default now()
);
create index if not exists clips_lookup_idx
  on clips (language, event_type, personality, humour_level, status, created_at);
-- Two workers generating at the same moment must not both store the same joke.
create unique index if not exists clips_unique_text_idx
  on clips (language, event_type, normalized_text);

create table if not exists plays (
  device_id uuid not null references devices (id) on delete cascade,
  clip_id uuid not null references clips (id) on delete cascade,
  kind text not null check (kind in ('served', 'played')),
  at timestamptz not null,
  primary key (device_id, clip_id, kind)
);

create table if not exists jobs (
  id uuid primary key,
  event_type text not null,
  language text not null,
  personality text not null,
  humour_level smallint not null,
  requested_by uuid null,
  force_new boolean not null default false,
  status text not null default 'pending',
  attempts integer not null default 0,
  error text,
  created_at timestamptz not null default now(),
  finished_at timestamptz
);
create index if not exists jobs_pending_idx
  on jobs (language, event_type, personality, humour_level, created_at) where status = 'pending';

create table if not exists usage_daily (
  day date primary key,
  grok_calls integer not null default 0,
  tts_characters bigint not null default 0,
  bytes_stored bigint not null default 0
);

create table if not exists device_quota (
  device_id uuid not null,
  day date not null,
  used integer not null default 0,
  primary key (device_id, day)
);

create table if not exists pronunciation (
  id serial primary key,
  language text not null,
  term text not null,
  normalized_term text not null,
  spoken text null,
  ipa text null,
  reviewed boolean not null default false,
  updated_at timestamptz not null default now(),
  unique (language, normalized_term)
);

create table if not exists pronunciation_reports (
  id uuid primary key,
  device_id uuid not null,
  clip_id uuid null,
  text text not null,
  language text not null,
  note text not null,
  status text not null default 'open',
  created_at timestamptz not null default now()
);

-- Starter set of Qatar place names.
-- NEEDS REVIEW BY A NATIVE QATARI SPEAKER: the vowelled `spoken` forms below are a first guess
-- and have NOT been checked by anyone. They are stored with reviewed = false, and unreviewed
-- entries are never applied to speech. A reviewer corrects each one and sets reviewed = true
-- through PUT /v1/admin/pronunciation. normalized_term must equal similarity.normalize(term).
insert into pronunciation (language, term, normalized_term, spoken, reviewed) values
  ('ar-QA', 'الدوحة',   'الدوحه',   'الدَّوْحَة',    false),
  ('ar-QA', 'الوكرة',   'الوكره',   'الوَكْرَة',    false),
  ('ar-QA', 'الخور',    'الخور',    'الخُور',      false),
  ('ar-QA', 'لوسيل',    'لوسيل',    'لُوسَيْل',     false),
  ('ar-QA', 'الريان',   'الريان',   'الرَّيَّان',    false),
  ('ar-QA', 'الكورنيش', 'الكورنيش', 'الكُورْنِيش',  false),
  ('ar-QA', 'سوق واقف', 'سوق واقف', 'سُوق وَاقِف',  false),
  ('ar-QA', 'كتارا',    'كتارا',    'كَتَارَا',     false),
  ('ar-QA', 'مشيرب',    'مشيرب',    'مْشِيرِب',     false),
  ('ar-QA', 'الدفنة',   'الدفنه',   'الدَّفْنَة',    false)
on conflict (language, normalized_term) do nothing;
