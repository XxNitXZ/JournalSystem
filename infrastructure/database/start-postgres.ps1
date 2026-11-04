#Requires -Version 5.1
<#
.SYNOPSIS
    Starts the local MediNexus PostgreSQL container (Windows / PowerShell).

.DESCRIPTION
    Idempotent start script for the MediNexus development database.

      * No container yet  -> checks port 5432, asks for a password, creates the
                             volume (if missing) and the container.
      * Container stopped -> checks port 5432 and starts it.
      * Container running -> reuses it.

    In every case the script verifies that an existing container matches the
    expected image, volume mount and port binding, and waits for PostgreSQL to
    accept connections (pg_isready) with a bounded number of retries.

    The script never deletes, recreates or overwrites containers or volumes.

    macOS equivalent: start-postgres.sh (keep the configuration identical).

.EXAMPLE
    .\infrastructure\database\start-postgres.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-postgres.sh
# ---------------------------------------------------------------------------
$PostgresImage     = 'postgres:18'
$ContainerName     = 'medinexus-postgres'
$VolumeName        = 'medinexus-postgres-data'
$DatabaseName      = 'medinexus_db'
$DatabaseUser      = 'medinexus'
$HostAddress       = '127.0.0.1'
$HostPort          = '5432'
$ContainerPort     = '5432'
$DataMountPath     = '/var/lib/postgresql'
$InitDbArgs        = '--auth-local=scram-sha-256 --auth-host=scram-sha-256'
$ReadyMaxAttempts  = 30
$ReadyDelaySeconds = 2
$PasswordAttempts  = 3

# Go templates used with "docker inspect". They contain no double quotes on
# purpose: Windows PowerShell 5.1 does not escape embedded quotes when passing
# arguments to native programs.
$MountsTemplate = '{{range .Mounts}}{{.Type}}|{{.Name}}|{{.Destination}};{{end}}'
$PortsTemplate  = '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'

$ExpectedImages = @($PostgresImage, "docker.io/library/$PostgresImage")
$ExpectedMount  = "volume|$VolumeName|$DataMountPath"
$ExpectedPorts  = "$ContainerPort/tcp=${HostAddress}:${HostPort};"

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
# Docker helper
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

# Returns the container state (running, exited, ...) or $null if it does not exist.
function Get-ContainerState {
    $result = Invoke-Docker @('container', 'inspect', '--format', '{{.State.Status}}', $ContainerName)
    if ($result.ExitCode -eq 0) {
        return $result.StdOut
    }
    if ($result.StdErr -match 'no such container') {
        return $null
    }
    Stop-WithError "Could not inspect container '$ContainerName': $($result.StdErr)"
}

function Test-VolumeExists {
    $result = Invoke-Docker @('volume', 'inspect', '--format', '{{.Name}}', $VolumeName)
    if ($result.ExitCode -eq 0) {
        return $true
    }
    if ($result.StdErr -match 'no such volume') {
        return $false
    }
    Stop-WithError "Could not inspect volume '$VolumeName': $($result.StdErr)"
}

function Get-InspectValue([string]$Template) {
    $result = Invoke-Docker @('container', 'inspect', '--format', $Template, $ContainerName)
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not inspect container '$ContainerName': $($result.StdErr)"
    }
    return $result.StdOut
}

# Verifies that an existing container uses the expected image, volume and port.
# Never modifies the container; reports conflicts and stops instead.
function Assert-ContainerConfiguration {
    $image  = Get-InspectValue '{{.Config.Image}}'
    $mounts = Get-InspectValue $MountsTemplate
    $ports  = Get-InspectValue $PortsTemplate

    $conflicts = @()
    if ($ExpectedImages -notcontains $image) {
        $conflicts += "Image:  expected '$PostgresImage', found '$image'"
    }
    if (@($mounts.Split(';')) -notcontains $ExpectedMount) {
        $conflicts += "Volume: expected volume '$VolumeName' at '$DataMountPath', found '$mounts'"
    }
    if ($ports -ne $ExpectedPorts) {
        $conflicts += "Port:   expected '$ExpectedPorts', found '$ports'"
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

# Returns a list of human-readable descriptions of anything using the host port.
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
            $conflicts += "Listening on $($listener.LocalAddress):$($listener.LocalPort) - PID $($listener.OwningProcess) ($processName)"
        }
    }

    if ($conflicts.Count -eq 0) {
        # Fallback probe in case the listener could not be enumerated.
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $connect = $client.ConnectAsync($HostAddress, [int]$HostPort)
            if ($connect.Wait(1000) -and $client.Connected) {
                $conflicts += "Something accepts connections on ${HostAddress}:${HostPort}"
            }
        }
        catch {
            # Connection refused: the port is free.
        }
        finally {
            $client.Dispose()
        }
    }

    return $conflicts
}

function Assert-PortFree {
    $conflicts = @(Get-PortConflicts)
    if ($conflicts.Count -gt 0) {
        $details = ($conflicts | ForEach-Object { "         - $_" }) -join "`n"
        Stop-WithError ("Host port $HostPort is already in use:`n$details`n" +
            "       Stop the other PostgreSQL server or container and run this script again.`n" +
            "       See infrastructure/database/README.md -> Troubleshooting.")
    }
    Write-Success "Host port $HostPort is free."
}

function Assert-VolumeNotInUse {
    $result = Invoke-Docker @('ps', '--all', '--filter', "volume=$VolumeName", '--format', '{{.Names}}')
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not check which containers use volume '$VolumeName': $($result.StdErr)"
    }
    if ($result.StdOut) {
        $names = ($result.StdOut -split "`n") -join ', '
        Stop-WithError ("Volume '$VolumeName' is already used by other container(s): $names`n" +
            "       Two PostgreSQL servers must never share one data directory. Resolve this manually.")
    }
}

# ---------------------------------------------------------------------------
# Password input
# ---------------------------------------------------------------------------
function ConvertTo-PlainText([System.Security.SecureString]$Secure) {
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Read-SecureInput([string]$Prompt) {
    try {
        return Read-Host -Prompt $Prompt -AsSecureString
    }
    catch {
        Stop-WithError 'A password is required on first initialization. Run this script in an interactive PowerShell window.'
    }
}

# Prompts (hidden input) for a new password; rejects empty or mismatching input.
function Read-NewDatabasePassword {
    for ($attempt = 1; $attempt -le $PasswordAttempts; $attempt++) {
        $password = ConvertTo-PlainText (Read-SecureInput "Enter a new password for database user '$DatabaseUser'")
        if ([string]::IsNullOrWhiteSpace($password)) {
            Write-Warn 'The password must not be empty.'
            continue
        }
        $confirmation = ConvertTo-PlainText (Read-SecureInput 'Confirm the password')
        if ($password -cne $confirmation) {
            Write-Warn 'The passwords do not match.'
            continue
        }
        return $password
    }
    Stop-WithError "No valid password entered after $PasswordAttempts attempts. Nothing was created."
}

# ---------------------------------------------------------------------------
# Docker actions (create / start only - never delete)
# ---------------------------------------------------------------------------
function New-DataVolume {
    Write-Info "Creating Docker volume '$VolumeName'..."
    $result = Invoke-Docker @('volume', 'create', $VolumeName)
    if ($result.ExitCode -ne 0) {
        Stop-WithError "Could not create volume '$VolumeName': $($result.StdErr)"
    }
    Write-Success "Volume '$VolumeName' created."
}

function New-PostgresContainer([string]$Password) {
    Write-Info "Creating container '$ContainerName' from '$PostgresImage' (the image is downloaded on first use)..."

    # The password is handed to docker through its process environment
    # ("--env POSTGRES_PASSWORD" without a value), so it never appears in the
    # command line. It is removed from this session immediately afterwards.
    $env:POSTGRES_PASSWORD = $Password
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker run `
            --detach `
            --name $ContainerName `
            --publish "${HostAddress}:${HostPort}:${ContainerPort}" `
            --volume "${VolumeName}:${DataMountPath}" `
            --env "POSTGRES_DB=$DatabaseName" `
            --env "POSTGRES_USER=$DatabaseUser" `
            --env POSTGRES_PASSWORD `
            --env "POSTGRES_INITDB_ARGS=$InitDbArgs" `
            $PostgresImage | Out-Null
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
        Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    }

    if ($exitCode -ne 0) {
        Stop-WithError ("'docker run' failed (exit code $exitCode). See the Docker output above. " +
            "If a container was created anyway, run this script again to start it.")
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

function Wait-PostgresReady {
    $maxSeconds = $ReadyMaxAttempts * $ReadyDelaySeconds
    Write-Info "Waiting for PostgreSQL to accept connections (up to $maxSeconds seconds)..."

    for ($attempt = 1; $attempt -le $ReadyMaxAttempts; $attempt++) {
        $state = Get-ContainerState
        if ($state -ne 'running') {
            Stop-WithError ("Container '$ContainerName' is no longer running (state: '$state').`n" +
                "       Check the logs with: docker logs $ContainerName")
        }

        # 127.0.0.1 here is the loopback interface inside the container. Using
        # TCP avoids a false positive while the image runs its first-time
        # initialization on a socket-only temporary server.
        $ready = Invoke-Docker @('exec', $ContainerName, 'pg_isready',
            '--host', '127.0.0.1', '--port', $ContainerPort,
            '--username', $DatabaseUser, '--dbname', $DatabaseName, '--quiet')
        if ($ready.ExitCode -eq 0) {
            Write-Success "PostgreSQL is ready (attempt $attempt of $ReadyMaxAttempts)."
            return
        }
        Start-Sleep -Seconds $ReadyDelaySeconds
    }

    Stop-WithError ("PostgreSQL did not become ready within $maxSeconds seconds.`n" +
        "       Check the logs with: docker logs $ContainerName")
}

function Show-ConnectionInfo {
    Write-Host ''
    Write-Host 'MediNexus PostgreSQL is running.' -ForegroundColor Green
    Write-Host "  Host:      $HostAddress"
    Write-Host "  Port:      $HostPort"
    Write-Host "  Database:  $DatabaseName"
    Write-Host "  Username:  $DatabaseUser"
    Write-Host "  JDBC URL:  jdbc:postgresql://${HostAddress}:${HostPort}/$DatabaseName"
    Write-Host "  Volume:    $VolumeName"
    Write-Host ''
    Write-Host "  Stop:      docker stop $ContainerName"
    Write-Host "  psql:      docker exec -it $ContainerName psql -U $DatabaseUser -d $DatabaseName"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
Write-Info "MediNexus PostgreSQL ($PostgresImage), container '$ContainerName'"
Assert-DockerAvailable

$containerState = Get-ContainerState

if ($null -ne $containerState) {
    Write-Info "Container '$ContainerName' already exists (state: $containerState)."
    Assert-ContainerConfiguration

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
    Write-Info "Container '$ContainerName' does not exist yet. Preparing first initialization."
    Assert-PortFree

    $volumeExists = Test-VolumeExists
    if ($volumeExists) {
        Assert-VolumeNotInUse
        Write-Warn ("Volume '$VolumeName' already exists and will be reused; its data is preserved. " +
            "If it already contains a database, that database keeps its original password " +
            "and the password entered below is ignored.")
    }

    $databasePassword = Read-NewDatabasePassword

    if (-not $volumeExists) {
        New-DataVolume
    }
    else {
        Write-Success "Reusing existing volume '$VolumeName'."
    }

    New-PostgresContainer $databasePassword
    $databasePassword = $null
}

Wait-PostgresReady
Show-ConnectionInfo
exit 0
