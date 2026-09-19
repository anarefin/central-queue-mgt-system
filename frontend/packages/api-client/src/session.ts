import { ApiClient, type ApiClientOptions, type TokenResponse } from "./client";
import { ApiRequestError } from "./errors";

export type AuthStatus = "unknown" | "authenticated" | "anonymous";

export interface AuthSessionOptions {
  /** Refresh this many seconds before the access token expires. */
  refreshSkewSeconds?: number;
  /** Delay before retrying a refresh that failed only because the network was down. */
  networkRetrySeconds?: number;
  setTimer?: (handler: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * Holds the access token in memory only (API-017): never in localStorage, sessionStorage or a script-readable cookie.
 * The refresh token lives in an HttpOnly cookie the browser sends by itself. Because refresh tokens are single-use with
 * reuse detection, concurrent refreshes MUST share one request, or the second would look like a replay and end the
 * session.
 */
export class AuthSession {
  status: AuthStatus = "unknown";

  private token: string | null = null;
  private timer: unknown;
  private inflight: Promise<boolean> | undefined;
  private readonly listeners = new Set<() => void>();
  private readonly skew: number;
  private readonly networkRetry: number;
  private readonly setTimer: (handler: () => void, ms: number) => unknown;
  private readonly clearTimer: (handle: unknown) => void;

  constructor(
    private readonly client: ApiClient,
    options: AuthSessionOptions = {},
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

  /** On page load: try to resume a session from the refresh cookie. Never throws. */
  async restore(): Promise<void> {
    await this.refresh();
  }

  async login(username: string, password: string): Promise<TokenResponse> {
    const response = await this.client.auth.login(username, password);
    this.accept(response);
    return response;
  }

  /** Single-flight silent refresh. Resolves true if a fresh access token is now held. */
  refresh(): Promise<boolean> {
    if (this.inflight) return this.inflight;
    this.inflight = (async () => {
      try {
        this.accept(await this.client.auth.refresh());
        return true;
      } catch (error) {
        if (error instanceof ApiRequestError && error.code === "network_error" && this.token) {
          this.schedule(this.networkRetry * 1000); // the token may not have been consumed; try again shortly
          return false;
        }
        this.drop();
        return false;
      } finally {
        this.inflight = undefined;
      }
    })();
    return this.inflight;
  }

  async logout(): Promise<void> {
    try {
      await this.client.auth.logout();
    } catch {
      // Signing out locally must work even if the server cannot be reached.
    } finally {
      this.drop();
    }
  }

  private accept(response: TokenResponse): void {
    this.token = response.access_token;
    this.status = "authenticated";
    this.schedule(Math.max(response.expires_in - this.skew, 1) * 1000);
    this.notify();
  }

  private drop(): void {
    this.token = null;
    this.status = "anonymous";
    this.cancel();
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

/** Builds an ApiClient and AuthSession wired together: the client reads the session's token and retries once after a refresh. */
export function createAuth(options: Pick<ApiClientOptions, "apiOrigin" | "fetch" | "getLanguage">, sessionOptions?: AuthSessionOptions) {
  // eslint-disable-next-line prefer-const
  let session!: AuthSession;
  const client = new ApiClient({
    ...options,
    getAccessToken: () => session.accessToken,
    onTokenInvalid: () => session.refresh(),
  });
  session = new AuthSession(client, sessionOptions);
  return { client, session };
}
