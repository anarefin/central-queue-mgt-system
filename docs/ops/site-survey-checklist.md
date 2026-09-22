# Site survey checklist and hardware/environment specification

Covers NFR-ENV-001, NFR-ENV-002, NFR-ENV-003, SRS §24. Ticket 60. Hand this to the client before installation; it is
theirs to give to a hardware vendor and a facilities/IT contact (SRS §24: "Hardware is the client's purchase, but
the specification must be theirs to hand to a vendor").

## Site survey (NFR-ENV-003: must be completed before installation)

For every site in scope, record:

- [ ] **Zone list with floor labels** - every waiting zone, which floor, and its display name in the admin console.
- [ ] **Counter positions** - a numbered layout (sketch or floor plan) showing every counter and which zone it is in.
- [ ] **Network drop locations** - a wired Ethernet point at each kiosk, counter indicator, display player and
      speaker amplifier location (SRS §24.1: "wired Ethernet preferred"); note any location relying on Wi-Fi instead.
- [ ] **Power points** - at every kiosk, counter, display player, amplifier and network switch location, plus
      whether each is on a UPS-protected circuit.
- [ ] **Display mounting points** - wall/ceiling mount or stand, viewing distance, and cable routing to power and
      network.
- [ ] **Speaker coverage** - acoustic coverage per zone, amplifier and speaker placement, and whether a 70/100V line
      is needed for a large lobby (SRS §24.1).
- [ ] **Glare and lighting** - kiosk and display screens must stay readable under lobby lighting; note any direct
      sunlight or spotlight the installer must route the mounting away from (NFR-ENV-002 makes this the client's
      responsibility, but the survey is where it gets caught).

## Hardware and environment specification (SRS §24.1, handed to the client's vendor)

| Device | Minimum specification |
| --- | --- |
| Kiosk | Intel i3 or ARM equivalent, 8 GB RAM, 128 GB SSD, 15-22 inch capacitive touch, Windows 10 IoT / Windows 11 / Android 11+, wired Ethernet preferred |
| Token printer | 80 mm thermal, ESC/POS, auto-cutter, USB or LAN, paper-low sensor |
| Display | 43-inch or larger, 1080p minimum, 16/7 duty rating, HDMI input |
| Display player | Android TV 11+ or small-form-factor PC, or a built-in system-on-chip if it runs a modern browser |
| Audio | Amplifier plus ceiling or wall speakers per zone, 70/100V line for large lobbies |
| Agent console | Any device with a modern browser, 1366x768 minimum |
| Network | 100 Mbps switched LAN per site; PoE recommended for displays and speakers |
| UPS | Sized for kiosk, printer, switch and display player, 15 minutes minimum |

## Kiosk environment requirements (NFR-ENV-001, NFR-ENV-002)

- [ ] Kiosk operating range: **10-40 degrees C**, up to **80% non-condensing relative humidity**. Confirm the
      installation point is not next to an entrance, kitchen, or other source of temperature/humidity swings outside
      this range.
- [ ] Screen readability: position every kiosk and display away from direct glare (windows, spotlights); this is the
      client's own responsibility per NFR-ENV-002 and must be resolved before go-live, not discovered afterwards.

## Sizing (SRS §24.2-§24.4)

Derive kiosk, display and speaker counts from the layout, not a guess:

- **Kiosks** = entrances x expected peak issuances per minute / 4 (one kiosk handles roughly 4 issuances/minute
  sustainably); add one spare per building.
- **Displays** = one per waiting zone, plus one summary board per entrance lobby.
- **Server tier**: see `docs/adr` and SRS §24.3 for the Small/Medium/Large tiers by tickets/day, sites and counters;
  `docs/ops/installer.md`'s multi-node mode is how the Medium and Large tiers are deployed.
