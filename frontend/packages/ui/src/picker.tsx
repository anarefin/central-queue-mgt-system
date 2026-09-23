"use client";

import { useId, useMemo, useState, type KeyboardEvent } from "react";
import { cn } from "./cn";
import { SelectField } from "./components";

export interface PickerOption {
  value: string;
  label: string;
}

export interface PickerProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: PickerOption[];
  /** The empty choice's own label ("Choose…", "All", …); omit to require a value be picked. */
  placeholder?: string;
  /** True while `options` is still loading; disables the control and keeps it out of the search count. */
  loading?: boolean;
  disabled?: boolean;
  helpText?: string;
  error?: string;
  /** Shown in the listbox when the query matches nothing. */
  noMatchesLabel?: string;
  /** Above this many options the picker becomes a searchable combobox instead of a plain `<select>` (ticket 64,
   *  FR-MON-002); at or under it a plain select is simpler and just as fast to use. */
  searchThreshold?: number;
  /** Fired the first time this control is focused — the caller's cue to load `options` on demand (ticket 64)
   *  rather than before the picker is ever used. Safe to call on every focus: the caller decides whether a
   *  reload is needed. */
  onOpen?: () => void;
  className?: string;
}

const DEFAULT_SEARCH_THRESHOLD = 10;

/**
 * A list picked from options loaded on demand, in place of a raw id typed into a text field (ticket 64: the live
 * dashboard's site, zone, service group, service, priority class and agent filters, and the agent chosen for a
 * force action). At or under {@link PickerProps.searchThreshold} options it is a plain `<select>`
 * ({@link SelectField}); past it, it becomes a searchable combobox — a text input that filters the list as the
 * caller types, built by hand rather than the native `<datalist>` (whose filtering jsdom does not run, and whose
 * value is never constrained to one of the options).
 */
export function Picker({
  id,
  label,
  value,
  onChange,
  options,
  placeholder,
  loading = false,
  disabled = false,
  helpText,
  error,
  noMatchesLabel,
  searchThreshold = DEFAULT_SEARCH_THRESHOLD,
  onOpen,
  className,
}: PickerProps) {
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");

  const searchable = options.length > searchThreshold;
  const selected = options.find((option) => option.value === value) ?? null;

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return needle === "" ? options : options.filter((option) => option.label.toLowerCase().includes(needle));
  }, [options, query]);

  if (!searchable) {
    return (
      <SelectField
        id={id}
        label={label}
        value={value}
        disabled={disabled || loading}
        helpText={helpText}
        error={error}
        className={className}
        onChange={(event) => onChange(event.target.value)}
        onFocus={onOpen}
        options={[...(placeholder !== undefined ? [{ value: "", label: placeholder }] : []), ...options.map((option) => ({ value: option.value, label: option.label }))]}
      />
    );
  }

  function choose(option: PickerOption) {
    onChange(option.value);
    setQuery("");
    setOpen(false);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      setOpen(false);
      setQuery("");
    } else if (event.key === "Enter" && filtered.length === 1) {
      event.preventDefault();
      choose(filtered[0]!);
    }
  }

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label className="text-sm font-medium text-fg" htmlFor={id}>
        {label}
      </label>
      <div className="relative">
        <input
          id={id}
          role="combobox"
          type="text"
          autoComplete="off"
          aria-expanded={open}
          aria-controls={listboxId}
          disabled={disabled || loading}
          className="w-full rounded-md border border-border bg-surface px-3 py-2 text-fg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
          value={open ? query : (selected?.label ?? "")}
          placeholder={loading ? undefined : placeholder}
          onFocus={() => {
            setOpen(true);
            setQuery("");
            onOpen?.();
          }}
          onChange={(event) => {
            setQuery(event.target.value);
            setOpen(true);
          }}
          onKeyDown={onKeyDown}
          onBlur={() => {
            // A click on an option fires before blur reaches here only when it runs on mousedown, not click; a
            // short delay lets `choose` (triggered on click) land first.
            setTimeout(() => setOpen(false), 150);
          }}
        />
        {open && (
          <ul id={listboxId} role="listbox" aria-label={label} className="absolute z-10 mt-1 max-h-60 w-full overflow-auto rounded-md border border-border bg-surface p-1 shadow-lg">
            {filtered.length === 0 && <li className="px-3 py-2 text-sm text-fg-muted">{noMatchesLabel}</li>}
            {filtered.map((option) => (
              <li key={option.value}>
                <button
                  type="button"
                  role="option"
                  aria-selected={option.value === value}
                  className={cn("block w-full rounded px-3 py-2 text-start text-sm hover:bg-surface-muted", option.value === value && "bg-surface-muted font-medium")}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => choose(option)}
                >
                  {option.label}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-danger">
          {error}
        </p>
      ) : (
        helpText && <p className="text-sm text-fg-muted">{helpText}</p>
      )}
    </div>
  );
}
