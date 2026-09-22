#!/usr/bin/env bash
# NFR-POR-003, NFR-SEC-052 (SRS §26, §24.1): the reference locked-down shell for a Linux kiosk or display box
# (small-form-factor PC or a system-on-chip running a modern browser). No browser chrome, no file-system access, no
# devtools, no way to navigate away from the configured app. "No exit without an administrator code" is enforced by
# the OS session, not this script: exiting Chromium (Alt+F4 is disabled below; only a hard power-cycle or an SSH/
# console session authenticated with the device's own admin credentials can end the kiosk session), matching how
# Windows kiosk hardware uses Assigned Access (Ctrl+Alt+Del -> sign out, gated by the admin account password) and
# Android hardware uses Screen Pinning or MDM-managed kiosk mode (gated by a device PIN) - see
# docs/ops/tls-and-kiosk-display-shell.md for all three.
#
# Usage: QMS_KIOSK_URL=https://qms.example.org/kiosk/ deploy/kiosk/launch-chromium-kiosk.sh
#        QMS_KIOSK_URL=https://qms.example.org/display/?zone=... deploy/kiosk/launch-chromium-kiosk.sh   (display box)
set -euo pipefail

: "${QMS_KIOSK_URL:?set QMS_KIOSK_URL to the kiosk or display app HTTPS URL (NFR-SEC-010: HTTP is not an option)}"

case "$QMS_KIOSK_URL" in
  https://*) ;;
  http://localhost*|http://127.0.0.1*) echo "launch-chromium-kiosk: WARNING - plain HTTP is only acceptable for a local dry run, never production (NFR-SEC-010)." ;;
  *) echo "launch-chromium-kiosk: QMS_KIOSK_URL must be HTTPS in production (NFR-SEC-010: HTTP MUST NOT be an option)." >&2; exit 1 ;;
esac

CHROMIUM="$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)"
if [ -z "$CHROMIUM" ]; then
  echo "launch-chromium-kiosk: no Chromium/Chrome binary found on PATH." >&2
  exit 1
fi

PROFILE_DIR="${QMS_KIOSK_PROFILE_DIR:-/var/lib/qms-kiosk/chromium-profile}"
mkdir -p "$PROFILE_DIR"

exec "$CHROMIUM" \
  --kiosk \
  --noerrdialogs \
  --disable-infobars \
  --disable-session-crashed-bubble \
  --disable-translate \
  --disable-pinch \
  --overscroll-history-navigation=0 \
  --no-first-run \
  --disable-features=TranslateUI \
  --check-for-update-interval=31536000 \
  --disable-component-update \
  --disable-dev-tools \
  --disable-context-menu \
  --autoplay-policy=no-user-gesture-required \
  --user-data-dir="$PROFILE_DIR" \
  --app="$QMS_KIOSK_URL"
