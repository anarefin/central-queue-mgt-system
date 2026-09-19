/**
 * The single seam every print goes through (FR-INT-050): Phase 1 is the browser/OS print dialog over a dedicated
 * print-only slip already rendered on the page; Phase 2 swaps in an ESC/POS thermal-printer driver behind the same
 * interface, with no UI rewrite. The kiosk always creates the Ticket through the API first and only then tries to
 * print it (FR-ISS-016), so a rejected `print()` here never means a lost ticket — the caller shows the token number
 * and a QR fallback instead.
 */
export interface PrintPayload {
  tokenNumber: string;
  serviceName: string;
  groupName: string;
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
