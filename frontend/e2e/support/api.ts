import { APIRequestContext, expect } from "@playwright/test";

/**
 * A thin REST client for test setup only (creating the Site/Service/Counter/staff a scenario needs before the
 * actual UAT steps run through the UI) — the same endpoints deploy/loadtest/seed.sh drives, kept in one place so a
 * spec's own body reads as the UAT steps, not fixture plumbing (SRS §27.2 describes the *scenario*, not how the
 * queue that hosts it was set up).
 */
export class Api {
    constructor(
        private readonly request: APIRequestContext,
        private readonly baseURL: string
    ) {}

    async login(username: string, password: string): Promise<string> {
        const res = await this.request.post(`${this.baseURL}/api/v1/auth/login`, { data: { username, password } });
        expect(res.ok(), await res.text()).toBeTruthy();
        return (await res.json()).access_token as string;
    }

    private async post<T>(path: string, token: string, body: unknown): Promise<T> {
        const res = await this.request.post(`${this.baseURL}/api/v1${path}`, {
            headers: { Authorization: `Bearer ${token}` },
            data: body ?? {},
        });
        expect(res.ok(), `${path}: ${await res.text()}`).toBeTruthy();
        return (await res.json()) as T;
    }

    async createSite(token: string, name: string): Promise<string> {
        const site = await this.post<{ id: string }>("/sites", token, {
            name,
            code: `E2E-${Date.now()}`,
            timezone: "Asia/Dhaka",
            address: "E2E fixture",
            default_language: "en",
            enabled_languages: ["en", "bn"],
        });
        return site.id;
    }

    async createZone(token: string, siteId: string): Promise<string> {
        const zone = await this.post<{ id: string }>(`/sites/${siteId}/zones`, token, { name: "Hall", floor_label: "1st" });
        return zone.id;
    }

    async createCounter(token: string, zoneId: string, label: string): Promise<string> {
        const counter = await this.post<{ id: string }>(`/zones/${zoneId}/counters`, token, { label });
        return counter.id;
    }

    async createServiceGroup(token: string, siteId: string, name: string): Promise<string> {
        const group = await this.post<{ id: string }>(`/sites/${siteId}/service-groups`, token, {
            name_i18n: { en: name, bn: name },
            token_prefix: name.slice(0, 1).toUpperCase(),
        });
        return group.id;
    }

    async createService(token: string, groupId: string, name: string): Promise<string> {
        const service = await this.post<{ id: string }>(`/service-groups/${groupId}/services`, token, {
            name_i18n: { en: name, bn: name },
            token_prefix: "A",
            expected_minutes: 5,
            sla_wait_minutes: 30,
            channels: ["reception", "kiosk"],
            booking_mode: "walk_in_only",
        });
        return service.id;
    }

    async linkCounter(token: string, serviceId: string, counterId: string): Promise<void> {
        await this.request.put(`${this.baseURL}/api/v1/services/${serviceId}/counters/${counterId}`, {
            headers: { Authorization: `Bearer ${token}` },
            data: {},
        });
    }

    async createStaff(token: string, role: "reception_operator" | "agent" | "team_admin" | "org_admin", siteId: string, username: string, password: string): Promise<string> {
        const user = await this.post<{ id: string }>("/users", token, {
            username,
            password,
            display_name: username,
            preferred_language: "en",
            roles: [{ role, site_ids: siteId ? [siteId] : [], group_ids: [] }],
        });
        return user.id;
    }

    async addToTeam(token: string, groupId: string, userId: string): Promise<void> {
        await this.post(`/service-groups/${groupId}/team/members`, token, { user_id: userId });
    }

    /** Issues a ticket the way a Reception Operator does (POST /tickets), for scenarios that need a ticket to
     * already exist before the UAT steps proper start (e.g. U6's second, priority-reclassified ticket). */
    async issueTicket(receptionToken: string, serviceId: string): Promise<{ id: string; token_number: string }> {
        const res = await this.request.post(`${this.baseURL}/api/v1/tickets`, {
            headers: { Authorization: `Bearer ${receptionToken}`, "Idempotency-Key": `e2e-${Date.now()}-${Math.random()}` },
            data: { service_id: serviceId },
        });
        expect(res.ok(), await res.text()).toBeTruthy();
        return res.json();
    }
}
