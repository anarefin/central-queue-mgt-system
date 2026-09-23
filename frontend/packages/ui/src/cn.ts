import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Composes conditional class names, letting a later Tailwind utility override an earlier conflicting one. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
