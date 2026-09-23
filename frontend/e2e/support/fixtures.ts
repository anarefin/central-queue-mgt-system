import { test as base } from "@playwright/test";
import { Api } from "./api";
import { activeProfile, ProfileFixture } from "./profiles";

export interface Queue {
    siteId: string;
    zoneId: string;
    groupId: string;
    serviceId: string;
    counterId: string;
    serviceName: string;
    /** The seeded starter Service's own Bangla name (ticket 70's U12: a Bangla-rendered kiosk tile has no English
     * accessible name to match by). */
    serviceNameBn: string;
    reception: { username: string; password: string };
    agent: { username: string; password: string };
    profile: ProfileFixture;
    /** The fixture admin's own token (system_admin, from QMS_BOOTSTRAP_ADMIN_USERNAME/PASSWORD) — reused by specs
     * that need further admin-only setup (device pairing, audit reads) instead of logging in again. */
    adminToken: string;
}

interface Fixtures {
    api: Api;
    /** A fresh Site/Zone/Counter plus a Reception Operator and an Agent on its team, via the API (SRS §27.2's
     * scenarios describe the queue's behaviour, not how the fixture queue was set up). The Service group/Service
     * come from the target deployment's own active vertical profile (ticket 67's seed endpoint), not hand-created,
     * so the fixture Queue's vocabulary always matches whichever profile is actually applied (ticket 70). Needs
     * QMS_BOOTSTRAP_ADMIN_USERNAME/QMS_BOOTSTRAP_ADMIN_PASSWORD for the deployment under test (the system_admin
     * created on first boot, per deploy/compose.yaml), and a profile already applied via the setup wizard. */
    queue: Queue;
}

export const test = base.extend<Fixtures>({
    api: async ({ request, baseURL }, use) => {
        await use(new Api(request, baseURL!));
    },
    queue: async ({ api }, use) => {
        const adminUsername = process.env.QMS_BOOTSTRAP_ADMIN_USERNAME;
        const adminPassword = process.env.QMS_BOOTSTRAP_ADMIN_PASSWORD;
        if (!adminUsername || !adminPassword) {
            throw new Error("Set QMS_BOOTSTRAP_ADMIN_USERNAME and QMS_BOOTSTRAP_ADMIN_PASSWORD to the target deployment's system_admin");
        }
        const profile = activeProfile();
        // Date.now() alone can collide across specs that start within the same millisecond (found by actually
        // running the suite, ticket 70) — usernames and the Site's own display name must stay unique per run.
        const stamp = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const adminToken = await api.login(adminUsername, adminPassword);
        // Every scenario creates its own fresh Site (see the fixture's own doc comment below), and `POST /sites`
        // refuses a second Site once one exists unless `multi_site` is on (ticket 68) — banking and healthcare both
        // ship it off by default (§3.4). Idempotent, so redundant across parallel-ish test starts.
        await api.setFeatureFlag(adminToken, "multi_site", true);
        const siteId = await api.createSite(adminToken, `E2E ${profile.id} ${stamp}`);
        const zoneId = await api.createZone(adminToken, siteId);

        const seeded = await api.seedCatalogue(adminToken, siteId);
        const services = await api.groupServices(adminToken, seeded.serviceGroupId);
        const service = services.find((s) => s.name_i18n.en === profile.starterServiceName);
        if (!service) {
            throw new Error(
                `Seeded starter catalogue for profile "${profile.id}" has no service named "${profile.starterServiceName}" ` +
                    `(seeded: ${services.map((s) => s.name_i18n.en).join(", ") || "none"}); ` +
                    "has this profile actually been applied to the deployment under test via the setup wizard?"
            );
        }

        const counterId = await api.createCounter(adminToken, zoneId, "E2E desk");
        await api.linkCounter(adminToken, service.id, counterId);

        const reception = { username: `e2e-reception-${stamp}`, password: "E2E-Reception-9" };
        await api.createStaff(adminToken, "reception_operator", siteId, reception.username, reception.password);

        // PasswordPolicy's default minLength is 12 (SecurityProperties.PasswordRules) — "E2E-Agent-9" (11 chars)
        // was under it and only surfaced once the suite actually ran for real (ticket 70).
        const agent = { username: `e2e-agent-${stamp}`, password: "E2E-Agent-999" };
        const agentId = await api.createStaff(adminToken, "agent", siteId, agent.username, agent.password);
        await api.addToTeam(adminToken, seeded.serviceGroupId, agentId);

        await use({
            siteId,
            zoneId,
            groupId: seeded.serviceGroupId,
            serviceId: service.id,
            counterId,
            serviceName: profile.starterServiceName,
            serviceNameBn: service.name_i18n.bn,
            reception,
            agent,
            profile,
            adminToken,
        });
    },
});

export { expect } from "@playwright/test";
