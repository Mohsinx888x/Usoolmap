// The navigation events a clip can react to. `situation` is what the prompt tells the text model
// about the moment. `humour: false` marks moments where a joke would be the wrong tone: the clip
// is then a warm, light remark instead of a punchline. `care` adds a safety constraint.

/** @type {Record<string, {situation: string, humour: boolean, care?: string}>} */
export const EVENTS = {
  route_start: { situation: 'The driver has just started a trip and the route is ready.', humour: true },
  arrival: { situation: 'The driver has just arrived at the destination.', humour: true },
  reroute: {
    situation: 'The driver left the planned route and a new route was just calculated.',
    humour: true,
    care: 'Tease kindly; never blame or insult the driver.',
  },
  long_straight: { situation: 'There is a long stretch of road ahead with nothing to do but keep going.', humour: true },
  roundabout: { situation: 'The driver is approaching or going round a roundabout.', humour: true },
  u_turn: { situation: 'The driver has to turn around and go back the way they came.', humour: true },
  turn_done: { situation: 'The driver has just completed a turn correctly.', humour: true },
  traffic_delay: { situation: 'Traffic ahead is slow and the trip will take longer.', humour: true },
  speeding: {
    situation: 'The driver is going faster than the speed limit.',
    humour: false,
    care: 'A friendly nudge to ease off. Never make speeding sound fun, impressive or acceptable.',
  },
  speed_camera: {
    situation: 'There is a speed camera on the road ahead.',
    humour: true,
    care: 'Never suggest dodging the camera, braking hard, or speeding up again after it.',
  },
  signal_lost: { situation: 'The phone has lost its GPS signal, so guidance is paused.', humour: true },
  signal_back: { situation: 'The GPS signal has come back and guidance continues.', humour: true },
  stopped_long: {
    situation: 'The car has been standing still for a long time in the middle of a trip.',
    humour: true,
    care: 'The reason for the stop is unknown; do not assume anything about it.',
  },
  night_drive: {
    situation: 'The driver is on the road late at night.',
    humour: false,
    care: 'Keep it calm. If tiredness is mentioned, the only advice is to take a break.',
  },
};

export const EVENT_TYPES = Object.keys(EVENTS);
