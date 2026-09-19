import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";

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
