import { ApiClient, type ApiClientOptions } from "./client";
import type { DeviceKind, DeviceTokenResponse } from "./devices";
import { ApiRequestError } from "./errors";

export type DeviceAuthStatus = "unknown" | "paired" | "unpaired";

export interface PairedDevice {
  id: string;
  kind: DeviceKind;
  siteId: string;
  zoneId: string | null;
}

/**
 * Where a device's own refresh credential lives between runs (API-017: "an OS-permission-restricted file on kiosks
 * and displays"). This repo runs every app, kiosk and display included, as a plain Next.js browser page — there is no
 * Electron/Tauri or other native shell here to give a real OS-level file — so this interface is the seam a future
 * native kiosk/display shell would implement for real; {@link createIndexedDbCredentialStore} is the closest browser-only
 * approximation available today (never `localStorage`, which API-017 forbids outright).
 */
export interface DeviceCredentialStore {
  load(): Promise<string | null>;
  save(refreshToken: string): Promise<void>;
  clear(): Promise<void>;
}

export interface DeviceSessionOptions {
  /** Refresh this many seconds before the access token expires. */
  refreshSkewSeconds?: number;
  /** Delay before retrying a refresh that failed only because the network was down. */
  networkRetrySeconds?: number;
  setTimer?: (handler: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Holds a device's access token in memory only (API-017), like {@link import("./session").AuthSession}; the refresh
 * token is rotated on every use and persisted through a {@link DeviceCredentialStore}, since a device has no human
 * present to sign back in and no browser cookie jar of its own to rely on.
 */
export class DeviceSession {
  status: DeviceAuthStatus = "unknown";
  device: PairedDevice | null = null;

  private token: string | null = null;
  private refreshToken: string | null = null;
  private timer: unknown;
  private inflight: Promise<boolean> | undefined;
  private readonly listeners = new Set<() => void>();
  private readonly skew: number;
  private readonly networkRetry: number;
  private readonly setTimer: (handler: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;

  constructor(
    private readonly client: ApiClient,
    private readonly store: DeviceCredentialStore,
    options: DeviceSessionOptions = {},
  ) {
    this.skew = options.refreshSkewSeconds ?? 60;
    this.networkRetry = options.networkRetrySeconds ?? 10;
    this.setTimer = options.setTimer ?? ((handler, ms) => setTimeout(handler, ms));
    this.clearTimer = options.clearTimer ?? ((handle) => clearTimeout(handle as ReturnType<typeof setTimeout>));
  }

  get accessToken(): string | null {
    return this.token;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * On boot: resume a paired device from its stored refresh credential, or land on "unpaired" (show the pairing
   * screen). Never throws: a store that cannot be read (no IndexedDB, private browsing, …) is the same as nothing
   * stored, not a stuck "unknown" screen forever.
   */
  async restore(): Promise<void> {
    let stored: string | null;
    try {
      stored = await this.store.load();
    } catch {
      stored = null;
    }
    if (!stored) {
      this.status = "unpaired";
      this.notify();
      return;
    }
    this.refreshToken = stored;
    await this.refresh();
  }

  /** Exchanges a pairing code for the device's own credential (FR-OPS-011). */
  async pair(code: string): Promise<DeviceTokenResponse> {
    const response = await this.client.devices.pair(code);
    await this.accept(response);
    return response;
  }

  /** Single-flight silent refresh. Resolves true if a fresh access token is now held. */
  refresh(): Promise<boolean> {
    if (this.inflight) return this.inflight;
    this.inflight = (async () => {
      const raw = this.refreshToken;
      if (!raw) {
        this.status = "unpaired";
        this.notify();
        return false;
      }
      try {
        const response = await this.client.devices.refresh(raw);
        await this.accept(response);
        return true;
      } catch (error) {
        if (error instanceof ApiRequestError && error.code === "network_error" && this.token) {
          this.schedule(this.networkRetry * 1000); // the credential may not have been consumed; try again shortly
          return false;
        }
        await this.forget(); // revoked, expired, or reused: this device needs pairing again
        return false;
      } finally {
        this.inflight = undefined;
      }
    })();
    return this.inflight;
  }

  /** Forgets this device's credential entirely (revoked remotely, or an on-device "unpair" action) and drops the socket state. */
  async forget(): Promise<void> {
    this.token = null;
    this.refreshToken = null;
    this.device = null;
    this.status = "unpaired";
    this.cancel();
    try {
      await this.store.clear();
    } catch {
      // The in-memory session is what protects this run; a store that cannot be cleared is a lesser problem.
    }
    this.notify();
  }

  private async accept(response: DeviceTokenResponse): Promise<void> {
    this.token = response.access_token;
    this.refreshToken = response.refresh_token;
    this.device = { id: response.device_id, kind: response.kind, siteId: response.site_id, zoneId: response.zone_id };
    this.status = "paired";
    try {
      // The credential already works for this run, held in memory; a store that cannot be written to (no IndexedDB,
      // private browsing, a full disk) must not be reported as the pairing or refresh itself having failed.
      await this.store.save(response.refresh_token);
    } catch {
      // Next boot will need pairing again if the process does not stay alive, but this session keeps working.
    }
    this.schedule(Math.max(response.expires_in - this.skew, 1) * 1000);
    this.notify();
  }

  private schedule(ms: number): void {
    this.cancel();
    this.timer = this.setTimer(() => void this.refresh(), ms);
  }

  private cancel(): void {
    if (this.timer !== undefined) this.clearTimer(this.timer);
    this.timer = undefined;
  }

  private notify(): void {
    this.listeners.forEach((listener) => listener());
  }
}

const DB_NAME = "qms-device";
const STORE_NAME = "credentials";
const REFRESH_TOKEN_KEY = "refresh_token";

/**
 * The best available approximation of API-017's "OS-permission-restricted file" in a plain browser tab: IndexedDB is
 * private to this origin and never readable from script running on another one, and — unlike `localStorage`, which
 * API-017 explicitly forbids — is not a synchronous, trivially-scraped global. It is not a real OS file permission; a
 * native kiosk/display shell should supply a {@link DeviceCredentialStore} backed by one instead.
 */
export function createIndexedDbCredentialStore(): DeviceCredentialStore {
  function open(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB_NAME, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(STORE_NAME);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("could not open IndexedDB"));
    });
  }

  async function withStore<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await open();
    try {
      return await new Promise<T>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, mode);
        const request = run(tx.objectStore(STORE_NAME));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error ?? new Error("IndexedDB request failed"));
      });
    } finally {
      db.close();
    }
  }

  return {
    async load() {
      const value = await withStore("readonly", (store) => store.get(REFRESH_TOKEN_KEY));
      return typeof value === "string" ? value : null;
    },
    async save(refreshToken) {
      await withStore("readwrite", (store) => store.put(refreshToken, REFRESH_TOKEN_KEY));
    },
    async clear() {
      await withStore("readwrite", (store) => store.delete(REFRESH_TOKEN_KEY));
    },
  };
}

/** Builds an ApiClient and DeviceSession wired together: the client reads the session's token and retries once after a refresh. */
export function createDeviceAuth(
  options: Pick<ApiClientOptions, "apiOrigin" | "fetch" | "getLanguage">,
  store: DeviceCredentialStore = createIndexedDbCredentialStore(),
  sessionOptions?: DeviceSessionOptions,
) {
  // eslint-disable-next-line prefer-const
  let session!: DeviceSession;
  const client = new ApiClient({
    ...options,
    getAccessToken: () => session.accessToken,
    onTokenInvalid: () => session.refresh(),
  });
  session = new DeviceSession(client, store, sessionOptions);
  return { client, session };
}
