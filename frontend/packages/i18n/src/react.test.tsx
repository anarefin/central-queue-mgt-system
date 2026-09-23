import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { I18nProvider, LabelsProvider, useI18n, useLabels } from "./react";

const TEST_PACK = vi.hoisted(() => ({
  meta: { locale: "en-US", numerals: "latn" as const },
  messages: {
    "fallback.text": "Text unavailable",
    "entity.visitor": "Visitor",
    "entity.visitor_id": "Visitor ID",
    "entity.service_group": "Service group",
    "entity.counter": "Counter",
    "entity.agent": "Agent",
    "entity.category": "Category",
    "entity.ticket": "Ticket",
    "probe.hello": "Hello {visitor}. {Visitor}s queue here. We served {visitors} today.",
    "probe.explicit": "Hello {visitor}.",
    "probe.sentenceInitialPlural": "{Tickets} waiting: {count}.",
  },
}));

vi.mock("./packs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./packs")>();
  return { ...actual, SHIPPED_PACKS: { en: TEST_PACK, bn: TEST_PACK } };
});

function EntityProbe({ entityKey }: { entityKey: "visitor" | "agent" | "counter" }) {
  const { entity } = useLabels();
  return <span data-testid="entity">{entity[entityKey]}</span>;
}

function TemplateProbe({ tkey, params }: { tkey: string; params?: Record<string, string> }) {
  const { t } = useLabels();
  return <span data-testid="out">{t(tkey, params)}</span>;
}

function renderWithLabels(ui: React.ReactElement, labels?: Record<string, string> | null) {
  return render(
    <I18nProvider loadExtra={false} userLanguage="en">
      <LabelsProvider labels={labels}>{ui}</LabelsProvider>
    </I18nProvider>,
  );
}

describe("LabelsProvider / useLabels (ticket 69, SRS §3.2)", () => {
  it("falls back to the pack's default noun when no override exists", () => {
    renderWithLabels(<EntityProbe entityKey="visitor" />, null);
    expect(screen.getByTestId("entity")).toHaveTextContent("Visitor");
  });

  it("a missing key in the override map (rather than an absent map) still falls back per key", () => {
    renderWithLabels(<EntityProbe entityKey="agent" />, { "entity.visitor": "Customer" });
    expect(screen.getByTestId("entity")).toHaveTextContent("Agent");
  });

  it("uses a supplied override over the pack default", () => {
    renderWithLabels(<EntityProbe entityKey="visitor" />, { "entity.visitor": "Customer" });
    expect(screen.getByTestId("entity")).toHaveTextContent("Customer");
  });

  it("a healthcare override renders Patient where a banking one renders Customer (SRS §3.2 table)", () => {
    const banking = renderWithLabels(<EntityProbe entityKey="visitor" />, { "entity.visitor": "Customer" });
    expect(screen.getByTestId("entity")).toHaveTextContent("Customer");
    banking.unmount();

    renderWithLabels(<EntityProbe entityKey="visitor" />, { "entity.visitor": "Patient" });
    expect(screen.getByTestId("entity")).toHaveTextContent("Patient");
  });

  it("auto-injects {visitor}, {Visitor} and {visitors} into t()", () => {
    renderWithLabels(<TemplateProbe tkey="probe.hello" />, { "entity.visitor": "Customer" });
    // {visitor}/{visitors} (mid-sentence) are lower-cased regardless of the stored value's own casing; {Visitor}
    // (sentence-initial/standalone) keeps it capitalised.
    expect(screen.getByTestId("out")).toHaveTextContent("Hello customer. Customers queue here. We served customers today.");
  });

  it("injects a sentence-initial capitalised plural ({Tickets}) too, not just {Ticket}/{ticket}/{tickets}", () => {
    renderWithLabels(<TemplateProbe tkey="probe.sentenceInitialPlural" params={{ count: "3" }} />, { "entity.ticket": "Token" });
    expect(screen.getByTestId("out")).toHaveTextContent("Tokens waiting: 3.");
  });

  it("explicit params win over injected entity params", () => {
    renderWithLabels(<TemplateProbe tkey="probe.explicit" params={{ visitor: "Guest" }} />, { "entity.visitor": "Customer" });
    expect(screen.getByTestId("out")).toHaveTextContent("Hello Guest.");
  });

  it("upgrades plain useI18n().t() transparently, with no call-site change (ticket 69)", () => {
    function PlainCaller() {
      const { t } = useI18n();
      return <span data-testid="out">{t("probe.hello")}</span>;
    }
    render(
      <I18nProvider loadExtra={false} userLanguage="en">
        <LabelsProvider labels={{ "entity.visitor": "Customer" }}>
          <PlainCaller />
        </LabelsProvider>
      </I18nProvider>,
    );
    expect(screen.getByTestId("out")).toHaveTextContent("Hello customer. Customers queue here. We served customers today.");
  });

  it("throws when used outside a LabelsProvider", () => {
    function Bare() {
      useLabels();
      return null;
    }
    expect(() => render(<Bare />)).toThrow(/useLabels must be used inside/);
  });
});
