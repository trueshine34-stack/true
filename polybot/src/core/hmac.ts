/**
 * L2 request signing.
 *
 * The CLOB expects `urlsafe_b64encode(HMAC_SHA256(urlsafe_b64decode(secret), msg))`
 * with padding retained — Python's base64 helpers keep `=`, so we do too.
 */

function b64UrlToBytes(b64url: string): Uint8Array {
  const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const raw = atob(padded);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function bytesToB64Url(bytes: Uint8Array): string {
  let raw = '';
  for (let i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
  return btoa(raw).replace(/\+/g, '-').replace(/\//g, '_');
}

/**
 * @param secret  API secret as handed out by /auth/api-key (url-safe base64)
 * @param message `timestamp + METHOD + path [+ body]`
 */
export async function buildHmacSignature(
  secret: string,
  message: string,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw',
    b64UrlToBytes(secret) as unknown as ArrayBuffer,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign(
    'HMAC',
    key,
    new TextEncoder().encode(message) as unknown as ArrayBuffer,
  );
  return bytesToB64Url(new Uint8Array(sig));
}
