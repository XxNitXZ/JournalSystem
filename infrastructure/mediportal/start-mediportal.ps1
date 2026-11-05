#Requires -Version 5.1
<#
.SYNOPSIS
    Starts the MediPortal frontend container (Windows / PowerShell).

.DESCRIPTION
    Idempotent start script for the MediPortal frontend (Nginx) in Docker.

      * Image missing     -> builds medinexus-mediportal:local from
                             infrastructure/mediportal/Dockerfile with
                             MediPortal/ as the build context.
      * No container yet  -> checks port 5173 and creates the container.
      * Container stopped -> checks port 5173 and starts it.
      * Container running -> reuses it.

    The MediCore API URL used by the BROWSER is embedded into the JavaScript
    bundle when the image is built (VITE_API_BASE_URL, default
    http://localhost:8080). It is recorded as the image label
    'medinexus.api-base-url' so the script can detect a mismatch.

    The script never deletes, recreates or overwrites containers, images,
    networks or volumes. Rebuilding the image does NOT update an existing
    container; see infrastructure/README.md.

    macOS equivalent: start-mediportal.sh (keep the configuration identical).

.EXAMPLE
    .\infrastructure\mediportal\start-mediportal.ps1

.EXAMPLE
    # Build with a different browser-facing API URL (only used when the image is built):
    $env:VITE_API_BASE_URL = 'http://localhost:8080'
    .\infrastructure\mediportal\start-mediportal.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-mediportal.sh
# ---------------------------------------------------------------------------
$ImageName         = 'medinexus-mediportal:local'
$ContainerName     = 'medinexus-mediportal'
$NetworkName       = 'medinexus-network'
$BackendContainer  = 'medinexus-medicore'
$HostAddress       = '127.0.0.1'
$HostPort          = '5173'
$ContainerPort     = '8080'
$DefaultApiBaseUrl = 'http://localhost:8080'
$ApiUrlLabel       = 'medinexus.api-base-url'
$ReadyMaxAttempts  = 15
$ReadyDelaySeconds = 1

$RepoRoot     = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Dockerfile   = Join-Path $RepoRoot 'infrastructure\mediportal\Dockerfile'
$BuildContext = Join-Path $RepoRoot 'MediPortal'
$FrontendUrl  = "http://${HostAddress}:${HostPort}/"

# Go templates used with "docker inspect" (no double quotes: see start-postgres.ps1).
$PortsTemplate    = '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'
$NetworksTemplate = '{{range $k, $v := .NetworkSettings.Networks}}{{$k}};{{end}}'
$LabelTemplate    = "{{if .Config.Labels}}{{index .Config.Labels ``$ApiUrlLabel``}}{{end}}"

$ExpectedPorts = "$ContainerPort/tcp=${HostAddress}:${HostPort};"

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
function Write-Info([string]$Message)    { Write-Host "==> $Message" }
function Write-Success([string]$Message) { Write-Host "OK: $Message" -ForegroundColor Green }
function Write-Warn([string]$Message)    { Write-Host "WARNING: $Message" -ForegroundColor Yellow }

function Stop-WithError([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# Docker helpers
# ---------------------------------------------------------------------------
# Runs docker and captures stdout, stderr and the exit code without letting
# Windows PowerShell 5.1 turn stderr output into terminating errors.
function Invoke-Docker([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $records = & docker @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    $stdout = @($records | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
    $stderr = @($records | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })

    [pscustomobject]@{
        ExitCode = $exitCode
        StdOut   = ($stdout -join "`n").Trim()
        StdErr   = ($stderr -join "`n").Trim()
    }
}

# Runs docker with its output shown live (used for long-running builds).
function Invoke-DockerLive([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker @Arguments
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------
function Assert-DockerAvailable {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithError ("The Docker CLI was not found. Install Docker Desktop for Windows " +
            "(https://docs.docker.com/desktop/) and open a new terminal.")
    }

    $info = Invoke-Docker @('info', '--format', '{{.OSType}}')
    if ($info.ExitCode -ne 0) {
        Stop-WithError ("Docker Desktop is not running or not reachable. Start Docker Desktop, " +
            "wait until the engine is running, then run this script again.`n       Docker said: $($info.StdErr)")
    }
    if ($info.StdOut -ne 'linux') {
        Stop-WithError ("Docker is using '$($info.StdOut)' containers. Switch Docker Desktop to " +
            "Linux containers and run this script again.")
    }
    Write-Success 'Docker Desktop is available.'
}

function Assert-NetworkExists {
    $result = Invoke-Docker @('network', 'inspect', '--format', '{{.Name}}', $NetworkName)
    if ($result.ExitCode -ne 0) {
        if ($result.StdErr -match 'not found|no such network') {
            Stop-WithError ("The Docker network '$NetworkName' does not exist.`n" +
                "       Run the PostgreSQL start script first; it creates the network:`n" +
                "         .\infrastructure\database\start-postgres.ps1")
        }
        Stop-WithError "Could not inspect network '$NetworkName': $($result.StdErr)"
    }
    Write-Success "Docker network '$NetworkName' exists."
}

# The API URL is used by the user's browser, so it must be reachable from the
# host - not a Docker container name such as http://medinexus-medicore:8080.
function Get-ApiBaseUrl {
    $url = $env:VITE_API_BASE_URL
    if ([string]::IsNullOrWhiteSpace($url)) {
        $url = $DefaultApiBaseUrl
    }
    else {
        Write-Info "Using VITE_API_BASE_URL '$url' from this PowerShell session."
    }
    $url = $url.Trim().TrimEnd('/')

    if ($url -notmatch '^https?://(\[[0-9a-fA-F:]+\]|[A-Za-z0-9.-]+)(:[0-9]+)?(/.*)?$') {
        Stop-WithError "VITE_API_BASE_URL '$url' is not a valid http(s) URL, for example $DefaultApiBaseUrl"
    }
    $hostName = $Matches[1]
    if ($hostName -ne 'localhost' -and $hostName -notmatch '\.' -and $hostName -notmatch '^\[') {
        Stop-WithError ("VITE_API_BASE_URL '$url' uses the host '$hostName', which looks like a Docker container name.`n" +
            "       The browser calls this URL, and browsers cannot resolve container names.`n" +
            "       Use an address reachable from the browser, for example $DefaultApiBaseUrl")
    }
    return $url
}

# Returns the state of a container (running, exited, ...) or $null if it does not exist.
function Get-ContainerState([string]$Name) {
    $result = Invoke-Docker @('container', 'inspect', '--format', '{{.State.Status}}', $Name)
    if ($result.ExitCode -eq 0) {
        return $result.StdOut
    }
    if ($result.StdErr -match 'no such container') {
        return $null
    }
    Stop-WithError "Could not inspect container '$Name': $($result.StdErr)"
}

function Get-InspectValue([string]$Name, [string]$Template) {
    $result = Invoke-Docker @('container', 'inspect', '--format', $Template, $Name)
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not inspect container '$Name': $($result.StdErr)"
    }
    return $result.StdOut
}

function Get-ImageId {
    $result = Invoke-Docker @('image', 'inspect', '--format', '{{.Id}}', $ImageName)
    if ($result.ExitCode -eq 0) {
        return $result.StdOut
    }
    if ($result.StdErr -match 'no such image') {
        return $null
    }
    Stop-WithError "Could not inspect image '$ImageName': $($result.StdErr)"
}

function Get-RebuildCommand([string]$ApiBaseUrl) {
    return ("docker build --file infrastructure/mediportal/Dockerfile --tag $ImageName " +
        "--build-arg VITE_API_BASE_URL=$ApiBaseUrl --label $ApiUrlLabel=$ApiBaseUrl MediPortal")
}

# Builds the image only when it does not exist yet; otherwise checks that it
# was built for the requested API URL.
function Initialize-Image([string]$ApiBaseUrl) {
    if ($null -eq (Get-ImageId)) {
        Write-Info "Image '$ImageName' not found. Building it with VITE_API_BASE_URL=$ApiBaseUrl..."
        $exitCode = Invoke-DockerLive @('build',
            '--file', $Dockerfile,
            '--tag', $ImageName,
            '--build-arg', "VITE_API_BASE_URL=$ApiBaseUrl",
            '--label', "$ApiUrlLabel=$ApiBaseUrl",
            $BuildContext)
        if ($exitCode -ne 0) {
            Stop-WithError "'docker build' failed (exit code $exitCode). See the Docker output above."
        }
        Write-Success "Image '$ImageName' built."
        return
    }

    $result = Invoke-Docker @('image', 'inspect', '--format', $LabelTemplate, $ImageName)
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not inspect image '$ImageName': $($result.StdErr)"
    }
    $builtFor = $result.StdOut
    if (-not $builtFor) {
        Write-Warn ("Image '$ImageName' has no '$ApiUrlLabel' label, so its API URL cannot be checked. " +
            "If unsure, rebuild it from the project root:`n         $(Get-RebuildCommand $ApiBaseUrl)")
    }
    elseif ($builtFor -ne $ApiBaseUrl) {
        Stop-WithError ("Image '$ImageName' was built for the API URL '$builtFor', but '$ApiBaseUrl' was requested.`n" +
            "       The URL is embedded at build time. Rebuild the image from the project root:`n" +
            "         $(Get-RebuildCommand $ApiBaseUrl)")
    }
    else {
        Write-Success "Image '$ImageName' exists and was built for the API URL '$builtFor'."
    }
}

# Verifies that an existing container matches the expected configuration.
# Never modifies the container; reports conflicts and stops instead.
function Assert-ContainerConfiguration {
    $image    = Get-InspectValue $ContainerName '{{.Config.Image}}'
    $ports    = Get-InspectValue $ContainerName $PortsTemplate
    $networks = Get-InspectValue $ContainerName $NetworksTemplate

    $conflicts = @()
    if ($image -ne $ImageName) {
        $conflicts += "Image:    expected '$ImageName', found '$image'"
    }
    if ($ports -ne $ExpectedPorts) {
        $conflicts += "Port:     expected '$ExpectedPorts', found '$ports'"
    }
    if (@($networks.Split(';')) -notcontains $NetworkName) {
        $conflicts += "Network:  expected '$NetworkName', found '$networks'"
    }

    if ($conflicts.Count -gt 0) {
        $details = ($conflicts | ForEach-Object { "         - $_" }) -join "`n"
        Stop-WithError ("A container named '$ContainerName' already exists but its configuration differs:`n" +
            "$details`n" +
            "       The script will not modify or delete it. Inspect it with 'docker inspect $ContainerName'.`n" +
            "       To keep it and let this script create a new one, rename it first, for example:`n" +
            "         docker rename $ContainerName $ContainerName-old")
    }
    Write-Success "Existing container '$ContainerName' matches the expected configuration."
}

# Rebuilding the image does not change an existing container: tell the user.
function Write-OutdatedImageWarning {
    $containerImageId = Get-InspectValue $ContainerName '{{.Image}}'
    $currentImageId = Get-ImageId
    if ($null -ne $currentImageId -and $containerImageId -ne $currentImageId) {
        Write-Warn ("Container '$ContainerName' was created from an older build of '$ImageName'. " +
            "It keeps serving the old version until you recreate it:`n" +
            "         docker stop $ContainerName`n" +
            "         docker rm $ContainerName`n" +
            "         .\infrastructure\mediportal\start-mediportal.ps1")
    }
}

# Returns a list of human-readable descriptions of anything using the host port.
# Checks all addresses: the Vite dev server listens on ::1 (IPv6 localhost).
function Get-PortConflicts {
    $conflicts = @()

    $published = Invoke-Docker @('ps', '--filter', "publish=$HostPort", '--format', '{{.Names}}')
    if ($published.ExitCode -eq 0 -and $published.StdOut) {
        $names = ($published.StdOut -split "`n") -join ', '
        $conflicts += "Docker container(s) already publishing port ${HostPort}: $names"
    }

    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $listeners = @()
        try {
            $listeners = @(Get-NetTCPConnection -LocalPort ([int]$HostPort) -State Listen -ErrorAction Stop)
        }
        catch {
            $listeners = @()
        }
        foreach ($listener in $listeners) {
            $processName = 'unknown process'
            try {
                $processName = (Get-Process -Id $listener.OwningProcess -ErrorAction Stop).ProcessName
            }
            catch {
                $processName = 'unknown process'
            }
            $hint = ''
            if ($processName -eq 'node') {
                $hint = ' - probably the Vite development server (npm run dev)'
            }
            $conflicts += "Listening on $($listener.LocalAddress):$($listener.LocalPort) - PID $($listener.OwningProcess) ($processName)$hint"
        }
    }

    if ($conflicts.Count -eq 0) {
        # Fallback probe (IPv4 and IPv6 loopback) in case listeners could not be enumerated.
        foreach ($address in @('127.0.0.1', '::1')) {
            $client = New-Object System.Net.Sockets.TcpClient([System.Net.IPAddress]::Parse($address).AddressFamily)
            try {
                $connect = $client.ConnectAsync($address, [int]$HostPort)
                if ($connect.Wait(1000) -and $client.Connected) {
                    $conflicts += "Something accepts connections on ${address} port ${HostPort}"
                }
            }
            catch {
                # Connection refused: free on this address.
            }
            finally {
                $client.Dispose()
            }
        }
    }

    return $conflicts
}

function Assert-PortFree {
    $conflicts = @(Get-PortConflicts)
    if ($conflicts.Count -gt 0) {
        $details = ($conflicts | ForEach-Object { "         - $_" }) -join "`n"
        Stop-WithError ("Host port $HostPort is already in use:`n$details`n" +
            "       If the Vite development server is running (npm run dev), stop it with Ctrl+C.`n" +
            "       Then run this script again.")
    }
    Write-Success "Host port $HostPort is free."
}

# ---------------------------------------------------------------------------
# Docker actions (build / create / start only - never delete)
# ---------------------------------------------------------------------------
function New-MediPortalContainer {
    Write-Info "Creating container '$ContainerName' from '$ImageName'..."
    $result = Invoke-Docker @('run', '--detach',
        '--name', $ContainerName,
        '--network', $NetworkName,
        '--publish', "${HostAddress}:${HostPort}:${ContainerPort}",
        $ImageName)
    if ($result.ExitCode -ne 0) {
        Stop-WithError ("'docker run' failed (exit code $($result.ExitCode)): $($result.StdErr)`n" +
            "       If a container was created anyway, run this script again to start it.")
    }
    Write-Success "Container '$ContainerName' created."
}

function Start-ExistingContainer {
    Write-Info "Starting stopped container '$ContainerName'..."
    $result = Invoke-Docker @('start', $ContainerName)
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not start container '$ContainerName': $($result.StdErr)"
    }
    Write-Success "Container '$ContainerName' started."
}

function Wait-MediPortalReady {
    Write-Info "Waiting for MediPortal at $FrontendUrl..."
    for ($attempt = 1; $attempt -le $ReadyMaxAttempts; $attempt++) {
        $state = Get-ContainerState $ContainerName
        if ($state -ne 'running') {
            Stop-WithError ("Container '$ContainerName' is no longer running (state: '$state').`n" +
                "       Check the logs with: docker logs $ContainerName")
        }
        try {
            $response = Invoke-WebRequest -Uri $FrontendUrl -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200 -and $response.Content -match 'id="root"') {
                Write-Success "MediPortal is serving the frontend (attempt $attempt of $ReadyMaxAttempts)."
                return
            }
        }
        catch {
            # Nginx not ready yet: keep waiting.
        }
        Start-Sleep -Seconds $ReadyDelaySeconds
    }
    Stop-WithError ("MediPortal did not respond at $FrontendUrl.`n" +
        "       Check the logs with: docker logs $ContainerName")
}

function Show-ConnectionInfo([string]$ApiBaseUrl) {
    Write-Host ''
    Write-Host 'MediNexus MediPortal is running.' -ForegroundColor Green
    Write-Host "  Open:       http://localhost:${HostPort}   (use 'localhost': it is the origin MediCore's CORS allows)"
    Write-Host "  API URL:    $ApiBaseUrl   (embedded in the image, used by the browser)"
    Write-Host "  Container:  $ContainerName (image $ImageName, network $NetworkName)"
    Write-Host ''
    Write-Host "  Logs:       docker logs -f $ContainerName"
    Write-Host "  Stop:       docker stop $ContainerName"

    if ((Get-ContainerState $BackendContainer) -ne 'running') {
        Write-Host ''
        Write-Warn ("MediCore ('$BackendContainer') is not running, so the backend status shows Offline. " +
            "Start it with: .\infrastructure\medicore\start-medicore.ps1")
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
Write-Info "MediNexus MediPortal ($ImageName), container '$ContainerName'"
Assert-DockerAvailable
Assert-NetworkExists
$apiBaseUrl = Get-ApiBaseUrl
Initialize-Image $apiBaseUrl

$containerState = Get-ContainerState $ContainerName

if ($null -ne $containerState) {
    Write-Info "Container '$ContainerName' already exists (state: $containerState)."
    Assert-ContainerConfiguration
    Write-OutdatedImageWarning

    switch ($containerState) {
        'running' {
            Write-Success "Container '$ContainerName' is already running."
        }
        { $_ -in @('exited', 'created') } {
            Assert-PortFree
            Start-ExistingContainer
        }
        'paused' {
            Stop-WithError "Container '$ContainerName' is paused. Resume it with: docker unpause $ContainerName"
        }
        default {
            Stop-WithError ("Container '$ContainerName' is in state '$containerState' and cannot be started safely.`n" +
                "       Check the logs with: docker logs $ContainerName")
        }
    }
}
else {
    Write-Info "Container '$ContainerName' does not exist yet."
    Assert-PortFree
    New-MediPortalContainer
}

Wait-MediPortalReady
Show-ConnectionInfo $apiBaseUrl
exit 0
