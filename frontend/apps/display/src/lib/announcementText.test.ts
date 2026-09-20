import { describe, expect, it } from "vitest";
import { announcementText, spokenToken, type AnnouncementData } from "./announcementText";

function data(overrides: Partial<AnnouncementData> = {}): AnnouncementData {
  return {
    tokenNumber: "QC-045",
    tokenPrefix: "QC",
    tokenPrefixSpoken: { en: "Q C", bn: "কিউ সি" },
    counterLabel: "Desk 1",
    serviceNames: { en: "Consultation", bn: "পরামর্শ" },
    floorLabel: "Ground floor",
    ...overrides,
  };
}

describe("spokenToken (ticket 29, FR-DSP-030)", () => {
  it("speaks the prefix's configured spoken form followed by the digits in the given language", () => {
    expect(spokenToken(data(), "en")).toBe("Q C zero four five");
    expect(spokenToken(data(), "bn")).toBe("কিউ সি শূন্য চার পাঁচ");
  });

  it("falls back to the raw prefix when no spoken form is recorded for that language yet (FR-I18N-041)", () => {
    expect(spokenToken(data({ tokenPrefixSpoken: {} }), "en")).toBe("QC zero four five");
  });

  it("has no prefix to speak when the token carries none", () => {
    expect(spokenToken(data({ tokenPrefix: null, tokenNumber: "045" }), "en")).toBe("zero four five");
  });
});

describe("announcementText (ticket 29, FR-DSP-021)", () => {
  it("builds the template from token, service, counter and floor, per language", () => {
    const en = announcementText(data(), "en", false);
    expect(en).toContain("Q C zero four five");
    expect(en).toContain("Consultation");
    expect(en).toContain("Desk 1");
    expect(en).toContain("Ground floor");

    const bn = announcementText(data(), "bn", false);
    expect(bn).toContain("কিউ সি শূন্য চার পাঁচ");
    expect(bn).toContain("পরামর্শ");
  });

  it("never speaks the visitor's name when the Service flag is off, even if a name is present (FR-DSP-022 default)", () => {
    const text = announcementText(data({ visitorName: "Karim" }), "en", false);
    expect(text).not.toContain("Karim");
  });

  it("speaks the visitor's name only when the Service flag is on and a name is present (FR-DSP-022)", () => {
    const withName = announcementText(data({ visitorName: "Karim" }), "en", true);
    expect(withName).toContain("Karim");

    const flagOnNoName = announcementText(data({ visitorName: null }), "en", true);
    expect(flagOnNoName).not.toContain("Karim");
  });
});
