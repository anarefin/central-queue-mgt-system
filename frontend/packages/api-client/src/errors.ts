/** The closed set of API error codes (SRS §20.3, docs/api/error-codes.md). Clients branch on `code`, never on `message`. */
export const API_ERROR_CODES = [
  "unauthenticated",
  "invalid_credentials",
  "token_invalid",
  "forbidden",
  "account_locked",
  "validation_failed",
  "not_found",
  "method_not_allowed",
  "unsupported_media_type",
  "conflict",
  "service_closed",
  "rate_limited",
  "internal_error",
  "unavailable",
] as const;

export type ApiErrorCode = (typeof API_ERROR_CODES)[number];

/** Failures that never reached the API's envelope (offline, proxy error page, malformed body). */
export type ClientErrorCode = "network_error" | "unexpected_response";

export interface ApiErrorBody {
  code: ApiErrorCode;
  message: string;
  message_i18n?: Record<string, string>;
  details?: Record<string, unknown>;
  trace_id: string;
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode | ClientErrorCode;
  readonly body?: ApiErrorBody;

  constructor(status: number, code: ApiErrorCode | ClientErrorCode, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
    this.body = body;
  }

  get traceId(): string | undefined {
    return this.body?.trace_id;
  }
}

export function isApiErrorCode(value: unknown): value is ApiErrorCode {
  return typeof value === "string" && (API_ERROR_CODES as readonly string[]).includes(value);
}
