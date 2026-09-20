import type { PrintField } from "@qms/api-client";

/**
 * The single seam every print goes through (FR-INT-050): Phase 1 is the browser/OS print dialog over a dedicated
 * print-only slip already rendered on the page; Phase 2 swaps in an ESC/POS thermal-printer driver behind the same
 * interface, with no UI rewrite — which is why `payload` below carries the whole printable field set (ticket 27,
 * FR-CFG-031), not just what Phase 1 happens to need: a driver that talks straight to a receipt printer, with no DOM
 * to read from, needs the same data Phase 1's slip renders. The kiosk always creates the Ticket through the API
 * first and only then tries to print it (FR-ISS-016), so a rejected `print()` here never means a lost ticket — the
 * caller shows the token number and a QR fallback instead.
 */
export interface PrintPayload {
  tokenNumber: string;
  serviceName: string;
  groupName: string;
  building: string | null;
  floor: string | null;
  visitorCode: string | null;
  visitorName: string | null;
  visitorCategory: string | null;
  counter: string | null;
  /** ISO 8601; the caller formats it for display. */
  issueTime: string;
  estimatedWait: string | null;
  /** What the QR field, if enabled, encodes: the visitor's own ticket-status URL. */
  qrValue: string;
  noticeLine: string | null;
  /** Which of the fixed fields the admin's saved template enables (FR-CFG-031), in the template's own order. */
  fields: PrintField[];
  orgName: string;
  primaryColor: string;
  logoUrl: string | null;
}

export interface TokenPrinter {
  print(payload: PrintPayload): Promise<void>;
}

/**
 * Phase 1's only implementation: the caller renders `payload` into the page's print-only slip (the `.qms-print-slip`
 * region, shown only under `@media print`) before calling this, and `window.print()` picks it up. This environment
 * never reports whether paper actually came out (§28.2's "Phase 1 consequences": no printer-status signal is
 * available), so the one failure this can detect and report is the print API being absent altogether.
 */
export class BrowserTokenPrinter implements TokenPrinter {
  async print(_payload: PrintPayload): Promise<void> {
    if (typeof window === "undefined" || typeof window.print !== "function") {
      throw new Error("Printing is not available in this environment");
    }
    window.print();
  }
}
