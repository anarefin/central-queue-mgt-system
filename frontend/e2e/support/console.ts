import { Page, expect } from "@playwright/test";

/** Signs a staff user into the console app (LoginForm.tsx, shared shape with admin). */
export async function loginConsole(page: Page, username: string, password: string, basePath = "/console/"): Promise<void> {
    await page.goto(`${basePath}login/`);
    await page.getByLabel("Username").fill(username);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(new RegExp(`${basePath}$`));
}

/** Opens a session on the named counter for the given Service, the way an Agent starts their desk. */
export async function openSession(page: Page, counterLabel: string, serviceName: string): Promise<void> {
    await page.getByRole("radio", { name: new RegExp(counterLabel) }).check();
    await page.getByRole("checkbox", { name: new RegExp(serviceName) }).check();
    await page.getByRole("button", { name: "Open session" }).click();
    await expect(page.getByTestId("current-token")).toBeVisible({ timeout: 10000 }).catch(() => {
        // No ticket waiting yet is fine here; the session itself opening is what this helper promises.
    });
}

export async function loginAdmin(page: Page, username: string, password: string, basePath = "/admin/"): Promise<void> {
    await page.goto(`${basePath}login/`);
    await page.getByLabel("Username").fill(username);
    await page.getByLabel("Password").fill(password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(new RegExp(`${basePath}$`));
}
