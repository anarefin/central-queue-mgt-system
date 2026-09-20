import { ApiClient, type ApiClientOptions } from "./client";
import { ApiRequestError } from "./errors";
import type { VisitorTokenResponse } from "./visitor-account";

export type VisitorAuthStatus = "unknown" | "authenticated" | "anonymous";

export interface VisitorAuthSessionOptions {
  /** Refresh this many seconds before the access token expires. */
  refreshSkewSeconds?: number;
  /** Delay before retrying a refresh that failed only because the network was down. */
  networkRetrySeconds?: number;
  setTimer?: (handler: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
}

/**
 * A registered visitor's own signed-in session (ticket 41, FR-MOB-001): email + OTP instead of username + password,
 * a two-step {@link requestOtp}/{@link verifyOtp} in place of {@code AuthSession#login}, otherwise the identical
 * shape — the access token lives in memory only (API-017), and the refresh token rides an HttpOnly, Secure,
 * SameSite=Strict cookie the browser holds and sends by itself, never this code.
 */
export class VisitorAuthSession {
  status: VisitorAuthStatus = "unknown";

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
    options: VisitorAuthSessionOptions = {},
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

  /** Step 1: asks the server to email a fresh one-time code. Throws `rate_limited` if too many were asked for recently. */
  async requestOtp(email: string): Promise<void> {
    await this.client.visitorAuth.requestOtp(email);
  }

  /** Step 2: the code from that email signs the visitor in. */
  async verifyOtp(email: string, code: string): Promise<VisitorTokenResponse> {
    const response = await this.client.visitorAuth.verifyOtp(email, code);
    this.accept(response);
    return response;
  }

  /** Single-flight silent refresh. Resolves true if a fresh access token is now held. */
  refresh(): Promise<boolean> {
    if (this.inflight) return this.inflight;
    this.inflight = (async () => {
      try {
        this.accept(await this.client.visitorAuth.refresh());
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
      await this.client.visitorAuth.logout();
    } catch {
      // Signing out locally must work even if the server cannot be reached.
    } finally {
      this.drop();
    }
  }

  private accept(response: VisitorTokenResponse): void {
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

/** Builds an ApiClient and VisitorAuthSession wired together: the client reads the session's token and retries once after a refresh. */
export function createVisitorAuth(options: Pick<ApiClientOptions, "apiOrigin" | "fetch" | "getLanguage">, sessionOptions?: VisitorAuthSessionOptions) {
  // eslint-disable-next-line prefer-const
  let session!: VisitorAuthSession;
  const client = new ApiClient({
    ...options,
    getAccessToken: () => session.accessToken,
    onTokenInvalid: () => session.refresh(),
  });
  session = new VisitorAuthSession(client, sessionOptions);
  return { client, session };
}
