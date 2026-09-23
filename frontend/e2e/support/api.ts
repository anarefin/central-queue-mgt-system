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

    private async post<T>(path: string, token: string, body: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
        const res = await this.request.post(`${this.baseURL}/api/v1${path}`, {
            headers: { Authorization: `Bearer ${token}`, ...extraHeaders },
            data: body ?? {},
        });
        expect(res.ok(), `${path}: ${await res.text()}`).toBeTruthy();
        return (await res.json()) as T;
    }

    private async get<T>(path: string, token: string, query: Record<string, string | undefined> = {}): Promise<T> {
        const params: Record<string, string> = {};
        for (const [key, value] of Object.entries(query)) if (value !== undefined) params[key] = value;
        const res = await this.request.get(`${this.baseURL}/api/v1${path}`, {
            headers: { Authorization: `Bearer ${token}` },
            params,
        });
        expect(res.ok(), `${path}: ${await res.text()}`).toBeTruthy();
        return (await res.json()) as T;
    }

    private async put<T>(path: string, token: string, body: unknown): Promise<T> {
        const res = await this.request.put(`${this.baseURL}/api/v1${path}`, {
            headers: { Authorization: `Bearer ${token}` },
            data: body ?? {},
        });
        expect(res.ok(), `${path}: ${await res.text()}`).toBeTruthy();
        return (await res.json()) as T;
    }

    async createSite(token: string, name: string): Promise<string> {
        const site = await this.post<{ id: string }>("/sites", token, {
            name,
            // Date.now() alone can collide: several specs can call this within the same millisecond in a fast run
            // (ticket 70, found by actually running the suite for the first time), and Site.code is globally unique.
            code: `E2E-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            timezone: "Asia/Dhaka",
            address: "E2E fixture",
            default_language: "en",
            enabled_languages: ["en", "bn"],
        });
        return site.id;
    }

    async createZone(token: string, siteId: string, name = "Hall", floorLabel = "1st"): Promise<string> {
        const zone = await this.post<{ id: string }>(`/sites/${siteId}/zones`, token, { name, floor_label: floorLabel });
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

    /** Seeds a Site's starter catalogue and numbering from the deployment's active vertical profile (ticket 67),
     * used by the `queue` fixture instead of hand-creating a Service group/Service, so the fixture Queue's own
     * vocabulary matches whichever profile the target deployment actually has applied (ticket 70). */
    async seedCatalogue(token: string, siteId: string): Promise<{ serviceGroupId: string; created: { kind: string; name: string }[] }> {
        const result = await this.post<{ service_group_id: string; created: { kind: string; name: string }[] }>("/setup/seed-catalogue", token, { site_id: siteId });
        return { serviceGroupId: result.service_group_id, created: result.created };
    }

    async groupServices(token: string, groupId: string): Promise<{ id: string; name_i18n: Record<string, string> }[]> {
        const result = await this.get<{ items: { id: string; name_i18n: Record<string, string> }[] }>(`/service-groups/${groupId}/services`, token);
        return result.items;
    }

    /** Creates a device pairing code (ticket 24, FR-OPS-011); `kind` is `kiosk` or `display`. The raw code is
     * returned exactly once, matching the real admin flow this fixture is standing in for. */
    async createPairingCode(token: string, kind: "kiosk" | "display", siteId: string, zoneId?: string, label?: string): Promise<string> {
        const result = await this.post<{ code: string }>("/devices/pairing-codes", token, {
            kind,
            site_id: siteId,
            zone_id: zoneId ?? null,
            label: label ?? null,
        });
        return result.code;
    }

    async findDeviceByLabel(token: string, label: string): Promise<{ id: string } | undefined> {
        const result = await this.get<{ items: { id: string; label: string }[] }>("/devices", token);
        return result.items.find((d) => d.label === label);
    }

    /** Configures a paired display's own board columns (ticket 28, FR-DSP-001) — used by the healthcare vertical
     * scenario to confirm service names are suppressed on the public display for that profile. */
    async setDisplayColumns(token: string, deviceId: string, columns: string[]): Promise<void> {
        await this.put(`/devices/${deviceId}/display-config`, token, { columns });
    }

    /** Enables the Journey feature for the deployment (ticket 31, ticket 68's flag gate); idempotent. */
    async enableJourneys(token: string): Promise<void> {
        await this.put("/journey-settings", token, { enabled: true });
    }

    /** Sets one of the closed-vocabulary feature flags (ticket 68, `FeatureFlagKey`); idempotent. Used to turn
     * `multi_site` on before the fixture's first `createSite` call (ticket 70): every scenario creates its own
     * fresh Site so specs never share state, and `POST /sites` is gated behind `multi_site` once a first Site
     * exists (banking and healthcare both ship it `off` by default, §3.4) — a real Org Admin would flip this on
     * to run a multi-scenario acceptance pass the same way, it is not a test-only bypass of the gate itself. */
    async setFeatureFlag(token: string, key: string, enabled: boolean): Promise<void> {
        await this.put(`/setup/feature-flags/${key}`, token, { enabled });
    }

    async createJourneyTemplate(token: string, groupId: string, nameEn: string, ordered: boolean, serviceIds: string[]): Promise<{ id: string }> {
        return this.post<{ id: string }>(`/service-groups/${groupId}/journey-templates`, token, {
            name_i18n: { en: nameEn, bn: nameEn },
            ordered,
            display_order: 0,
            service_ids: serviceIds,
        });
    }

    async issueJourney(
        receptionToken: string,
        body: { journey_template_id?: string; service_ids?: string[]; ordered?: boolean }
    ): Promise<{ visit_id: string; ordered: boolean; stops: { seq: number; service_id: string; state: string; ticket?: { id: string; token_number: string } }[] }> {
        const res = await this.request.post(`${this.baseURL}/api/v1/journeys`, {
            headers: { Authorization: `Bearer ${receptionToken}`, "Idempotency-Key": `e2e-journey-${Date.now()}-${Math.random()}` },
            data: body,
        });
        expect(res.ok(), await res.text()).toBeTruthy();
        return res.json();
    }

    /** Reads the audit log (ticket 70's U9 assertion: `GET /audit` directly rather than a not-yet-confirmed admin
     * screen, per the ticket's own note). Requires `perm:audit:read` (system_admin/org_admin only). */
    async auditEntries(token: string, params: { entity?: string; entity_id?: string; action?: string } = {}): Promise<{ reason: string | null; action: string }[]> {
        const result = await this.get<{ items: { reason: string | null; action: string }[] }>("/audit", token, {
            entity: params.entity,
            entity_id: params.entity_id,
            action: params.action,
        });
        return result.items;
    }
}
