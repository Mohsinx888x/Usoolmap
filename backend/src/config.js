// Every setting comes from the environment. In Azure the secret ones are Key Vault references
// resolved by Container Apps, so this file never talks to Key Vault itself.

const num = (env, name, fallback) => {
  const raw = env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value)) throw new Error(`${name} must be a number, got "${raw}"`);
  return value;
};

/** @param {Record<string, string | undefined>} env */
export function loadConfig(env = process.env) {
  return {
    role: env.ROLE || 'all', // api | worker | all
    port: num(env, 'PORT', 8080),
    logLevel: env.LOG_LEVEL || 'info',

    tokenSecret: env.TOKEN_SECRET || '',
    appKey: env.APP_KEY || '',
    adminKey: env.ADMIN_KEY || '',

    databaseUrl: env.DATABASE_URL || '',
    storageConnectionString: env.AZURE_STORAGE_CONNECTION_STRING || '',
    storageAccount: env.AZURE_STORAGE_ACCOUNT || '',
    blobContainer: env.BLOB_CONTAINER || 'clips',
    queueName: env.QUEUE_NAME || 'voice-jobs',

    xaiApiKey: env.XAI_API_KEY || '',
    grokModel: env.GROK_MODEL || 'latest',
    speechKey: env.AZURE_SPEECH_KEY || '',
    speechRegion: env.AZURE_SPEECH_REGION || '',

    nonRepeatDays: num(env, 'NON_REPEAT_DAYS', 90),
    dailyDeviceQuota: num(env, 'DAILY_DEVICE_QUOTA', 20),
    maxJobsPerRequest: num(env, 'MAX_JOBS_PER_REQUEST', 3),
    starterPerEvent: num(env, 'STARTER_PER_EVENT', 3),
    similarityThreshold: num(env, 'SIMILARITY_THRESHOLD', 0.55),
    // A pending job older than this no longer blocks a new one for the same combination, so a
    // job whose queue message was lost cannot starve that combination for ever.
    pendingJobTtlMinutes: num(env, 'PENDING_JOB_TTL_MINUTES', 30),

    rateLimitBurst: num(env, 'RATE_LIMIT_BURST', 30),
    rateLimitPerMinute: num(env, 'RATE_LIMIT_PER_MINUTE', 30),

    dailyBudgetUsd: num(env, 'DAILY_BUDGET_USD', 5),
    priceGrokPerCallUsd: num(env, 'PRICE_GROK_PER_CALL_USD', 0.002),
    priceTtsPerMillionCharsUsd: num(env, 'PRICE_TTS_PER_MILLION_CHARS_USD', 16),

    jobVisibilityTimeoutSeconds: num(env, 'JOB_VISIBILITY_TIMEOUT_SECONDS', 120),
    jobMaxAttempts: num(env, 'JOB_MAX_ATTEMPTS', 4),
    workerPollSeconds: num(env, 'WORKER_POLL_SECONDS', 5),
  };
}

/** Names of the settings a role cannot start without; reported together so one deploy fixes all. */
export function missingSettings(config) {
  const required = { TOKEN_SECRET: config.tokenSecret, DATABASE_URL: config.databaseUrl };
  if (!config.storageConnectionString) required.AZURE_STORAGE_ACCOUNT = config.storageAccount;
  // The API needs the text model too (assistant commands); only the worker synthesises speech.
  required.XAI_API_KEY = config.xaiApiKey;
  if (config.role !== 'worker') Object.assign(required, { APP_KEY: config.appKey, ADMIN_KEY: config.adminKey });
  if (config.role !== 'api') Object.assign(required, { AZURE_SPEECH_KEY: config.speechKey, AZURE_SPEECH_REGION: config.speechRegion });
  return Object.keys(required).filter((name) => !required[name]);
}
