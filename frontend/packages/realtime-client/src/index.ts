/**
 * Stub. The WebSocket client (connect, `reauth` frame before token expiry, reconnect on `principal.changed`,
 * ADR-0009) arrives with the realtime hub in ticket 11.
 */
export interface RealtimeClientOptions {
  apiOrigin: string;
  getAccessToken: () => string | null;
}

export const REALTIME_CLIENT_READY = false;
