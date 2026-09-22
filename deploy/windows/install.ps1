# NFR-POR-001 (SRS §26.1): installs QMS on Windows Server from the same offline artefact bundle deploy/bundle.sh
# produces, mirroring deploy/preflight.sh and deploy/install.sh step for step so a consultant runs the same
# procedure regardless of host OS. Requires Docker Desktop / Docker Engine for Windows Server (with the containers
# feature enabled) or Podman Desktop; PowerShell 7+.
#
# Usage: .\install.ps1 -Bundle .\qms-offline-bundle-0.1.0.tar.gz
#   (set $env:QMS_DB_PASSWORD before running)

param(
    [Parameter(Mandatory = $true)][string]$Bundle,
    [switch]$SkipDb
)

$ErrorActionPreference = "Stop"
$failures = New-Object System.Collections.Generic.List[string]

function Write-Ok($msg) { Write-Host "  OK   $msg" }
function Write-Fail($msg) { $script:failures.Add($msg) }

Write-Host "QMS installer preflight (FR-OPS-001, FR-OPS-002) - Windows Server"
Write-Host "===================================================================="

# ---- OS (NFR-POR-001) ------------------------------------------------------------------------------------------
Write-Host "OS:"
$os = Get-CimInstance -ClassName Win32_OperatingSystem
if ($os.Caption -match "Server") {
    Write-Ok "$($os.Caption) $($os.Version)"
} else {
    Write-Host "  WARN $($os.Caption) is not Windows Server (NFR-POR-001 names Windows Server); proceeding for a dry run only."
}

# ---- Container runtime ------------------------------------------------------------------------------------------
Write-Host "Container runtime:"
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    try {
        docker info *> $null
        Write-Ok "Docker is installed and the daemon is reachable ($(docker --version))"
    } catch {
        Write-Fail "Docker is installed but its daemon is not reachable. Start Docker before installing."
    }
} else {
    $podman = Get-Command podman -ErrorAction SilentlyContinue
    if ($podman) {
        Write-Ok "Podman is installed ($(podman --version))"
    } else {
        Write-Fail "Neither Docker nor Podman is installed. NFR-POR-001 requires one of them."
    }
}

# ---- Disk space --------------------------------------------------------------------------------------------------
Write-Host "Disk space:"
$drive = (Get-Location).Drive
$freeGb = [math]::Round($drive.Free / 1GB, 1)
if ($freeGb -ge 10) {
    Write-Ok "$freeGb GB free on $($drive.Name): (minimum 10 GB)"
} else {
    Write-Fail "Only $freeGb GB free on $($drive.Name): - the Small server-sizing tier (SRS §24.3) needs at least 10 GB."
}

# ---- Clock synchronisation (FR-OPS-002) -----------------------------------------------------------------------
Write-Host "Clock synchronisation:"
try {
    $w32tm = w32tm /query /status 2>$null
    if ($w32tm -match "Source:\s*Local CMOS Clock") {
        Write-Fail "Windows Time (w32tm) is using the local CMOS clock, not an NTP source. FR-OPS-002 requires mandatory clock synchronisation; run 'w32tm /config /manualpeerlist:<ntp-server> /syncfromflags:manual /update' and re-run this script."
    } elseif ($w32tm) {
        Write-Ok "Windows Time service reports a synchronised source"
    } else {
        Write-Fail "Could not query the Windows Time service (w32tm). FR-OPS-002 requires clock sync; ensure the 'Windows Time' service is running."
    }
} catch {
    Write-Fail "Could not query clock synchronisation status: $_"
}

# ---- Database reachability (skipped on first install; same convention as deploy/preflight.sh) -------------------
if (-not $SkipDb -and $env:QMS_DB_URL) {
    Write-Host "Database reachability:"
    if ($env:QMS_DB_URL -match "^jdbc:postgresql://([^:/]+):?(\d*)/") {
        $dbHost = $Matches[1]
        $dbPort = if ($Matches[2]) { [int]$Matches[2] } else { 5432 }
        if (Test-NetConnection -ComputerName $dbHost -Port $dbPort -InformationLevel Quiet) {
            Write-Ok "$dbHost`:$dbPort accepts TCP connections"
        } else {
            Write-Fail "Could not reach $dbHost`:$dbPort. Check QMS_DB_URL and that the database is running."
        }
    } else {
        Write-Fail "QMS_DB_URL could not be parsed as jdbc:postgresql://host:port/db"
    }
}

Write-Host "===================================================================="
if ($failures.Count -gt 0) {
    Write-Host "PREFLIGHT FAILED: $($failures.Count) problem(s) found. Refusing to install (FR-OPS-001: no half-installs)."
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "PREFLIGHT PASSED."

# ---- Install from the offline bundle (NFR-POR-004: no downloads at install) --------------------------------------
if (-not (Test-Path $Bundle)) { throw "Bundle '$Bundle' not found" }
if (-not $env:QMS_DB_PASSWORD) { throw "Set `$env:QMS_DB_PASSWORD before running" }

$work = Join-Path $env:TEMP ("qms-install-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    Write-Host "Extracting the offline bundle..."
    tar -xzf $Bundle -C $work

    Write-Host "Loading container images from the bundle (no registry access needed)..."
    docker load -i (Join-Path $work "images.tar")

    Write-Host "Migrating, then starting the stack (FR-OPS-020: migration is a separate step before the app starts)..."
    $compose = Join-Path $work "deploy\compose.yaml"
    docker compose -f $compose run --rm migrate
    docker compose -f $compose up -d backend proxy

    Write-Host "Install complete. Health: curl http://localhost:8080/api/v1/health/dependencies"
    Write-Host "Next: complete the setup wizard (SRS §26.2), and put a TLS-terminating proxy in front before production traffic (NFR-SEC-010, docs\ops\tls-and-kiosk-display-shell.md)."
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
