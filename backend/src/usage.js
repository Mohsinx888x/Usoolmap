// Cost accounting. The figures are ESTIMATES from counters multiplied by configured unit prices;
// the real bill is whatever xAI and Azure invoice. The Azure budget alert in infra/ is the
// backstop for everything this estimate does not see (storage, database, compute, egress).

/** UTC calendar day `YYYY-MM-DD`: quotas, usage and the budget all roll over at 00:00 UTC. */
export const utcDay = (date) => date.toISOString().slice(0, 10);

export function estimateCostUsd({ grokCalls, ttsCharacters }, config) {
  const cost = grokCalls * config.priceGrokPerCallUsd + (ttsCharacters / 1_000_000) * config.priceTtsPerMillionCharsUsd;
  return Math.round(cost * 1e6) / 1e6;
}

/**
 * @param {{store: object, config: object, now?: () => Date}} deps
 */
export function createUsage({ store, config, now = () => new Date() }) {
  const dayRow = (day, row) => {
    const counters = {
      grokCalls: row?.grokCalls ?? 0,
      ttsCharacters: row?.ttsCharacters ?? 0,
      bytesStored: row?.bytesStored ?? 0,
    };
    return { date: day, ...counters, estimatedCostUsd: estimateCostUsd(counters, config) };
  };

  return {
    /** @param {{grokCalls?: number, ttsCharacters?: number, bytesStored?: number}} delta */
    record: ({ grokCalls = 0, ttsCharacters = 0, bytesStored = 0 }) =>
      store.addUsage(utcDay(now()), { grokCalls, ttsCharacters, bytesStored }),

    async isOverBudget() {
      const day = utcDay(now());
      const today = (await store.getUsage(day)).find((row) => row.day === day);
      return dayRow(day, today).estimatedCostUsd >= config.dailyBudgetUsd;
    },

    /** One row per day for the last `days` days, today first, zero-filled. */
    async report(days) {
      const dates = Array.from({ length: days }, (_, i) => utcDay(new Date(now().getTime() - i * 86_400_000)));
      const rows = new Map((await store.getUsage(dates.at(-1))).map((row) => [row.day, row]));
      return { dailyBudgetUsd: config.dailyBudgetUsd, days: dates.map((day) => dayRow(day, rows.get(day))) };
    },
  };
}
