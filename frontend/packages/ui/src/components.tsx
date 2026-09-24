import { useMemo, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "./cn";
import { encodeQrMatrix } from "./qrcode";

export function Page({ children }: { children: ReactNode }) {
  return <main className="mx-auto flex w-full max-w-[60rem] flex-col gap-4 px-4 py-8">{children}</main>;
}

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ButtonSize = "sm" | "md" | "lg";

const BUTTON_VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: "bg-primary text-primary-fg hover:bg-primary-hover active:bg-primary-active",
  secondary: "border border-border bg-surface text-fg hover:bg-surface-muted",
  ghost: "text-fg hover:bg-surface-muted",
  // A fixed red, not the --color-danger token: that token pairs with its own -subtle background for Badge and
  // turns pale in dark mode, unreadable as a solid fill with white text.
  danger: "bg-[#b91c1c] text-white hover:bg-[#a01818] active:bg-[#7f1414]",
};

const BUTTON_SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: "px-2.5 py-1.5 text-sm",
  md: "px-4 py-2 text-sm",
  lg: "px-5 py-2.5 text-base",
};

function ButtonSpinner() {
  return (
    <svg className="size-4 motion-safe:animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" opacity="0.25" />
      <path d="M22 12a10 10 0 0 1-10 10" stroke="currentColor" strokeWidth="4" strokeLinecap="round" />
    </svg>
  );
}

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  disabled,
  className,
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize; loading?: boolean }) {
  return (
    <button
      aria-busy={loading || undefined}
      disabled={disabled || loading}
      className={cn(
        "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md font-medium motion-safe:transition-colors pointer-coarse:min-h-11",
        "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary",
        "disabled:cursor-not-allowed disabled:opacity-60",
        BUTTON_VARIANT_CLASSES[variant],
        BUTTON_SIZE_CLASSES[size],
        className,
      )}
      {...props}
    >
      {loading && <ButtonSpinner />}
      {children}
    </button>
  );
}

export function Card({
  header,
  actions,
  className,
  children,
}: {
  header?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <section className={cn("flex flex-col gap-4 rounded-lg border border-border bg-surface p-4 shadow-sm sm:p-5", className)}>
      {(header || actions) && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          {header && <h2 className="font-semibold text-fg">{header}</h2>}
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold text-fg">{title}</h1>
        {description && <p className="text-fg-muted">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

export type BadgeVariant = "neutral" | "ok" | "warn" | "danger" | "info";

const BADGE_VARIANT_CLASSES: Record<BadgeVariant, string> = {
  neutral: "bg-surface-muted text-fg-muted",
  ok: "bg-ok-subtle text-ok",
  warn: "bg-warn-subtle text-warn",
  danger: "bg-danger-subtle text-danger",
  info: "bg-info-subtle text-info",
};

export function Badge({ variant = "neutral", children }: { variant?: BadgeVariant; children: ReactNode }) {
  return (
    <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", BADGE_VARIANT_CLASSES[variant])}>
      {children}
    </span>
  );
}

export function EmptyState({
  icon,
  title,
  body,
  action,
}: {
  icon?: ReactNode;
  title: ReactNode;
  body?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border p-8 text-center">
      {icon && (
        <div className="text-fg-muted" aria-hidden="true">
          {icon}
        </div>
      )}
      <p className="font-semibold text-fg">{title}</p>
      {body && <p className="text-fg-muted">{body}</p>}
      {action}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("rounded-md bg-surface-muted motion-safe:animate-pulse", className)} aria-hidden="true" />;
}

function fieldDescribedBy(id: string, hasHelp: boolean, hasError: boolean): string | undefined {
  if (hasError) return `${id}-error`;
  if (hasHelp) return `${id}-help`;
  return undefined;
}

/** The one look for every text-like control; use on raw `<input type="file">`/`<select>` that the field components do not cover. */
export const FIELD_INPUT_CLASSES =
  "w-full rounded-md border border-border bg-surface px-3 py-2 text-fg pointer-coarse:min-h-11 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary";

export function TextField({
  label,
  id,
  helpText,
  error,
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; id: string; helpText?: string; error?: string }) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label className="text-sm font-medium text-fg" htmlFor={id}>
        {label}
      </label>
      <input id={id} className={FIELD_INPUT_CLASSES} aria-invalid={error ? true : undefined} aria-describedby={fieldDescribedBy(id, Boolean(helpText), Boolean(error))} {...props} />
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : (
        helpText && (
          <p id={`${id}-help`} className="text-sm text-fg-muted">
            {helpText}
          </p>
        )
      )}
    </div>
  );
}

export type StatusKind = "up" | "down" | "not_configured";

const STATUS_BADGE_CLASSES: Record<StatusKind, string> = {
  up: "text-ok",
  down: "text-danger",
  not_configured: "text-warn",
};

/** A glyph beside the label so status never relies on colour alone. */
const STATUS_BADGE_ICONS: Record<StatusKind, string> = { up: "✓", down: "✕", not_configured: "!" };

export function StatusBadge({ status, children }: { status: StatusKind; children: ReactNode }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full border border-current px-2.5 py-0.5 text-sm font-semibold", STATUS_BADGE_CLASSES[status])}>
      <span aria-hidden="true">{STATUS_BADGE_ICONS[status]}</span>
      {children}
    </span>
  );
}

/**
 * The one alert component every refusal, validation and network-error message renders through across every app
 * (ticket 65's "consistent inline alert component": remote join's ~15 refusal reasons, OTP errors, the cancel
 * ticket_already_called refusal, and every admin/console error besides). `role="alert"` so assistive tech announces
 * it the moment it appears, with no action required from the reader.
 */
export function ErrorAlert({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-md border-s-4 border-danger bg-danger-subtle px-4 py-3 text-sm font-medium text-danger" role="alert">
      {children}
    </div>
  );
}

/**
 * A QR code rendered as inline SVG from {@link encodeQrMatrix} (no image, no network, no third-party package):
 * the kiosk's printer-failure fallback (FR-ISS-016) must render on-device every time. `label` is the accessible
 * name for screen readers, since the code itself conveys nothing to someone who cannot see or scan it. `value` is
 * also exposed as `data-qr-value` (ticket 65): the encoded URL itself, read directly by E2E U8 (ticket 70) rather
 * than re-decoding the rendered matrix.
 */
export function QrCode({ value, size = 200, label }: { value: string; size?: number; label: string }) {
  const matrix = useMemo(() => encodeQrMatrix(value), [value]);
  const modules = matrix.length;
  const quietZone = 4; // ISO/IEC 18004 §6.3.8: at least 4 modules of light margin so scanners can find the finders.
  const viewSize = modules + quietZone * 2;
  return (
    <svg
      viewBox={`0 0 ${viewSize} ${viewSize}`}
      width={size}
      height={size}
      role="img"
      aria-label={label}
      data-qr-value={value}
      className="mx-auto block rounded-md border border-border bg-white p-2"
      shapeRendering="crispEdges"
    >
      <rect x={0} y={0} width={viewSize} height={viewSize} fill="#fff" />
      {matrix.map((row, r) =>
        row.map((dark, c) => (dark ? <rect key={`${r}-${c}`} x={c + quietZone} y={r + quietZone} width={1} height={1} fill="#000" /> : null)),
      )}
    </svg>
  );
}

export function SelectField({
  label,
  id,
  options,
  helpText,
  error,
  className,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & {
  label: string;
  id: string;
  options: { value: string; label: string }[];
  helpText?: string;
  error?: string;
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label className="text-sm font-medium text-fg" htmlFor={id}>
        {label}
      </label>
      <select id={id} className={FIELD_INPUT_CLASSES} aria-invalid={error ? true : undefined} aria-describedby={fieldDescribedBy(id, Boolean(helpText), Boolean(error))} {...props}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : (
        helpText && (
          <p id={`${id}-help`} className="text-sm text-fg-muted">
            {helpText}
          </p>
        )
      )}
    </div>
  );
}

export function TextareaField({
  label,
  id,
  helpText,
  error,
  className,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; id: string; helpText?: string; error?: string }) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label className="text-sm font-medium text-fg" htmlFor={id}>
        {label}
      </label>
      <textarea id={id} rows={4} className={FIELD_INPUT_CLASSES} aria-invalid={error ? true : undefined} aria-describedby={fieldDescribedBy(id, Boolean(helpText), Boolean(error))} {...props} />
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : (
        helpText && (
          <p id={`${id}-help`} className="text-sm text-fg-muted">
            {helpText}
          </p>
        )
      )}
    </div>
  );
}

/** A native checkbox (or radio, via `type`) with its label: a 44px-tall tap row on touch, accent-coloured with the theme. */
export function CheckboxField({
  label,
  id,
  helpText,
  type = "checkbox",
  className,
  ...props
}: Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & { label: ReactNode; id: string; helpText?: string; type?: "checkbox" | "radio" }) {
  return (
    <div className={cn("flex flex-col gap-0.5", className)}>
      <label htmlFor={id} className="flex items-center gap-2.5 text-sm text-fg pointer-coarse:min-h-11">
        <input id={id} type={type} className="size-4 shrink-0 accent-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary" aria-describedby={helpText ? `${id}-help` : undefined} {...props} />
        <span>{label}</span>
      </label>
      {helpText && (
        <p id={`${id}-help`} className="ps-6.5 text-sm text-fg-muted">
          {helpText}
        </p>
      )}
    </div>
  );
}

/** A loading placeholder announced to assistive tech: spinner and message plus shimmering lines that hold the layout
 *  so content does not jump when it arrives. */
export function Loading({ children, lines = 3 }: { children: ReactNode; lines?: number }) {
  return (
    <div role="status" className="flex flex-col gap-3">
      <p className="flex items-center gap-2 text-fg-muted">
        <ButtonSpinner />
        {children}
      </p>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} className={index === lines - 1 ? "h-4 w-2/3" : "h-4 w-full"} />
      ))}
    </div>
  );
}
