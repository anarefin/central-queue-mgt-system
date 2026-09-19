import { useMemo, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from "react";
import { encodeQrMatrix } from "./qrcode";

export function Page({ children }: { children: ReactNode }) {
  return <main className="qms-page qms-stack">{children}</main>;
}

export function Card({ children }: { children: ReactNode }) {
  return <section className="qms-card qms-stack">{children}</section>;
}

export function Button({
  variant = "primary",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" }) {
  const className = variant === "secondary" ? "qms-button qms-button--secondary" : "qms-button";
  return <button className={className} {...props} />;
}

export function TextField({ label, id, ...props }: InputHTMLAttributes<HTMLInputElement> & { label: string; id: string }) {
  return (
    <div>
      <label className="qms-label" htmlFor={id}>
        {label}
      </label>
      <input className="qms-input" id={id} {...props} />
    </div>
  );
}

export type StatusKind = "up" | "down" | "not_configured";

export function StatusBadge({ status, children }: { status: StatusKind; children: ReactNode }) {
  return <span className={`qms-badge qms-badge--${status}`}>{children}</span>;
}

export function ErrorAlert({ children }: { children: ReactNode }) {
  return (
    <div className="qms-alert" role="alert">
      {children}
    </div>
  );
}

/**
 * A QR code rendered as inline SVG from {@link encodeQrMatrix} (no image, no network, no third-party package):
 * the kiosk's printer-failure fallback (FR-ISS-016) must render on-device every time. `label` is the accessible
 * name for screen readers, since the code itself conveys nothing to someone who cannot see or scan it.
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
      className="qms-qr"
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
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string; id: string; options: { value: string; label: string }[] }) {
  return (
    <div>
      <label className="qms-label" htmlFor={id}>
        {label}
      </label>
      <select className="qms-input" id={id} {...props}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
