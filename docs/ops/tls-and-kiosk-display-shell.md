# TLS everywhere, and the kiosk/display packaged shell

Covers NFR-SEC-010, NFR-POR-003, NFR-SEC-052 (SRS §25.2, §26, §24.1). Ticket 60.

## TLS 1.2+ everywhere, including the site LAN (NFR-SEC-010)

`deploy/compose.yaml`'s default `deploy/proxy/nginx.conf` serves plain HTTP on one origin; that is deliberately kept
as the default because it is what CI's `compose-smoke` job and a consultant's first local dry run use before any
certificate exists, and changing it would silently break that already-green path. **It MUST NOT be used in
production.** For production, or any real site LAN, mount `deploy/proxy/nginx.tls.conf.example` instead:

- terminates TLS 1.2/1.3 only (`ssl_protocols TLSv1.2 TLSv1.3`);
- redirects any plain-HTTP request on port 80 to HTTPS (`return 301 https://...`) - HTTP is never an option, even to
  serve a redirect insecurely for long;
- adds HSTS (`Strict-Transport-Security`).

Certificates: a client's own internal CA for a LAN-only deployment (kiosk and display devices trust that CA in their
kiosk-mode browser profile), or a public CA for a publicly reachable origin (needed regardless for the visitor
mobile web app and Web Push, ADR-0011). Either way, "including within a site LAN" means the counter, kiosk, display
and console traffic on the local network is TLS too, not only the public-facing pieces - there is exactly one
origin behind the proxy (ADR-0012), so terminating TLS once at the proxy covers every app.

## Kiosk and display packaged shells (NFR-POR-003, NFR-SEC-052)

NFR-POR-003: "Client applications (kiosk, console, display, admin) MUST be browser-based, with a packaged shell for
kiosk and TV hardware." NFR-SEC-052: "The kiosk application MUST run in a locked-down shell: no browser chrome, no
file system access, no exit without an administrator code." Kiosk hardware in this SRS (§24.1) is Windows 10
IoT/Windows 11, Android 11+, or a Linux small-form-factor PC; each platform's kiosk mode is the right way to meet
NFR-SEC-052's "no exit without an administrator code", because each is already a mature, OS-verified lockdown that
this codebase does not need to reinvent:

### Linux small-form-factor PC (kiosk or display)

`deploy/kiosk/launch-chromium-kiosk.sh` launches Chromium in `--kiosk` mode against the kiosk or display app's HTTPS
URL, with devtools, the context menu, pinch-zoom and update checks disabled, and its own isolated profile directory
(no access to the rest of the file system from inside the browser). Run it as the only thing an unprivileged local
user's session starts (a systemd user service or a `.xinitrc` with no window manager beyond what is needed to give
Chromium the whole screen). "No exit without an administrator code" is enforced at the OS session boundary, not
inside the script: Alt+F4/window-manager shortcuts are absent because there is no window manager chrome to catch
them, and ending the kiosk session requires a console or SSH login authenticated with the device's own admin
credentials (the same account and password an installer sets up during provisioning, out of this script's scope).

### Windows 10 IoT / Windows 11 (kiosk or display)

Use Windows **Assigned Access** (Settings > Accounts > Other users > Set up a kiosk, or the `Set-AssignedAccess`
PowerShell cmdlet) to lock a dedicated local account into full-screen Chromium/Edge pointed at the kiosk or display
URL with the same `--kiosk` flags `deploy/kiosk/launch-chromium-kiosk.sh` uses (Edge and Chromium share the flag
set). Assigned Access removes the taskbar, Start menu and every other way out of the app; exiting it requires
Ctrl+Alt+Del and signing out, which needs the administrator account's own password - that is the Windows platform's
"administrator code" for NFR-SEC-052.

### Android 11+ (kiosk or display)

Use Android's built-in **Screen Pinning** (single-device, PIN-gated) for a small deployment, or a proper **kiosk/MDM
launcher** (e.g. a fully-managed device with a kiosk launcher app set as the device owner) for a fleet - both make
the kiosk or display URL, opened in Chrome for Android's own kiosk mode, the only thing the device can show, and
both require a device PIN/admin credential to leave. Device provisioning is a per-fleet MDM decision the client's IT
makes, so this is documented rather than scripted.

In every case the packaged shell points at an HTTPS URL (see TLS section above) - a kiosk or display device on a
site LAN is exactly the "including within a site LAN" case NFR-SEC-010 calls out, so it is never pointed at plain
HTTP in production.
