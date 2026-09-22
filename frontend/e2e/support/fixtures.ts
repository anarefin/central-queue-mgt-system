import { test as base } from "@playwright/test";
import { Api } from "./api";
import { activeProfile, ProfileFixture } from "./profiles";

export interface Queue {
    siteId: string;
    groupId: string;
    serviceId: string;
    counterId: string;
    serviceName: string;
    reception: { username: string; password: string };
    agent: { username: string; password: string };
    profile: ProfileFixture;
}

interface Fixtures {
    api: Api;
    /** A fresh Site/ServiceGroup/Service/Counter plus a Reception Operator and an Agent on its team, via the API
     * (SRS §27.2's scenarios describe the queue's behaviour, not how the fixture queue was set up). Needs
     * QMS_BOOTSTRAP_ADMIN_USERNAME/QMS_BOOTSTRAP_ADMIN_PASSWORD for the deployment under test (the system_admin
     * created on first boot, per deploy/compose.yaml). */
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
        const stamp = Date.now();
        const adminToken = await api.login(adminUsername, adminPassword);
        const siteId = await api.createSite(adminToken, `E2E ${profile.id} ${stamp}`);
        const zoneId = await api.createZone(adminToken, siteId);
        const groupId = await api.createServiceGroup(adminToken, siteId, profile.starterServiceName);
        const serviceId = await api.createService(adminToken, groupId, profile.starterServiceName);
        const counterId = await api.createCounter(adminToken, zoneId, "E2E desk");
        await api.linkCounter(adminToken, serviceId, counterId);

        const reception = { username: `e2e-reception-${stamp}`, password: "E2E-Reception-9" };
        await api.createStaff(adminToken, "reception_operator", siteId, reception.username, reception.password);

        const agent = { username: `e2e-agent-${stamp}`, password: "E2E-Agent-9" };
        const agentId = await api.createStaff(adminToken, "agent", siteId, agent.username, agent.password);
        await api.addToTeam(adminToken, groupId, agentId);

        await use({ siteId, groupId, serviceId, counterId, serviceName: profile.starterServiceName, reception, agent, profile });
    },
});

export { expect } from "@playwright/test";
