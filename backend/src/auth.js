import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

const hmac = (secret, value) => createHmac('sha256', secret).update(value).digest('base64url');

/**
 * Constant-time string comparison. Both sides are hashed first so the comparison takes the same
 * time whatever the lengths are (timingSafeEqual itself refuses inputs of different length).
 */
export function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const digest = (value) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(a), digest(b));
}

/** Device token: `v1.<deviceId>.<HMAC-SHA256(secret, "v1.<deviceId>")>`. Stateless to verify. */
export function signDeviceToken(deviceId, secret) {
  const payload = `v1.${deviceId}`;
  return `${payload}.${hmac(secret, payload)}`;
}

/** @returns {string | null} the device id, or null when the token is malformed or forged */
export function verifyDeviceToken(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3 || parts[0] !== 'v1') return null;
  const [version, deviceId, signature] = parts;
  return safeEqual(signature, hmac(secret, `${version}.${deviceId}`)) ? deviceId : null;
}

/** The raw install id is never stored: only this keyed hash is, so a database leak cannot be
 *  used to register as somebody else's device. */
export function hashInstallId(installId, secret) {
  return hmac(secret, `install:${installId}`);
}

export function bearerToken(headerValue) {
  const match = /^Bearer\s+(\S+)$/i.exec(headerValue || '');
  return match ? match[1] : null;
}
