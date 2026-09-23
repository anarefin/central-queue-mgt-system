/**
 * The three profiles SRS §27.2 runs U1-U12 against (§27.3 adds the vertical-specific scenario per profile), and just
 * enough of each shipped profile's own data (SRS §3.4) for a spec to pick a real starter Service/priority-class name
 * without hardcoding one profile's vocabulary into every test. The active profile is read from QMS_PROFILE — a
 * deployment must already have applied it via the setup wizard (ticket 56); this harness does not apply profiles
 * itself, the same way it does not stand up the stack itself (§27.2: "config changed", not code, between runs).
 */
export type ProfileId = "banking" | "healthcare" | "producer_services";

export interface ProfileFixture {
    id: ProfileId;
    /** The first starter service the profile ships (§3.4), used to drive the kiosk/console flows generically. */
    starterServiceName: string;
    /** A priority class with a head-start > 0, for U6 (a priority visitor arriving into a long normal queue). */
    priorityClassWithHeadStart: string;
    /** The vertical-specific scenario's own label, matched against the visible entity label (§3.2 terminology remap). */
    visitorLabel: string;
}

export const PROFILES: Record<ProfileId, ProfileFixture> = {
    banking: {
        id: "banking",
        starterServiceName: "Cash deposit",
        priorityClassWithHeadStart: "Priority banking",
        visitorLabel: "Customer",
    },
    healthcare: {
        id: "healthcare",
        starterServiceName: "Registration",
        priorityClassWithHeadStart: "Emergency",
        visitorLabel: "Patient",
    },
    producer_services: {
        id: "producer_services",
        starterServiceName: "Helpdesk query",
        priorityClassWithHeadStart: "Distant-district producer",
        visitorLabel: "Producer",
    },
};

export function activeProfile(): ProfileFixture {
    const id = (process.env.QMS_PROFILE as ProfileId) || "banking";
    const profile = PROFILES[id];
    if (!profile) throw new Error(`Unknown QMS_PROFILE "${id}"; expected one of ${Object.keys(PROFILES).join(", ")}`);
    return profile;
}
