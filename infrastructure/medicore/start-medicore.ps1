#Requires -Version 5.1
<#
.SYNOPSIS
    Starts the MediCore backend container (Windows / PowerShell).

.DESCRIPTION
    Idempotent start script for the MediCore Spring Boot backend in Docker.

      * Image missing     -> builds medinexus-medicore:local from
                             infrastructure/medicore/Dockerfile.
      * No container yet  -> checks port 8080, determines DB_URL, DB_USERNAME
                             and DB_PASSWORD (session variables, then the
                             root .env file, then a prompt), verifies the
                             login against PostgreSQL and creates the
                             container.
      * Container stopped -> checks port 8080 and starts it.
      * Container running -> reuses it.

    Before that it checks that the Docker network 'medinexus-network' exists
    and that the PostgreSQL container is running, attached to that network and
    accepting connections. Afterwards it waits until MediCore reports
    status UP (including the database check).

    The script never deletes, recreates or overwrites containers, images,
    networks or volumes. Rebuilding the image does NOT update an existing
    container; see infrastructure/README.md.

    macOS equivalent: start-medicore.sh (keep the configuration identical).

.EXAMPLE
    .\infrastructure\medicore\start-medicore.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Shared configuration - must stay identical to start-medicore.sh
# ---------------------------------------------------------------------------
$ImageName         = 'medinexus-medicore:local'
$ContainerName     = 'medinexus-medicore'
$NetworkName       = 'medinexus-network'
$PostgresContainer = 'medinexus-postgres'
$PostgresPort      = '5432'
$DatabaseName      = 'medinexus_db'
$DefaultDbUser     = 'medinexus'
$DbUrl             = "jdbc:postgresql://${PostgresContainer}:${PostgresPort}/$DatabaseName"
$HostAddress       = '127.0.0.1'
$HostPort          = '8080'
$ContainerPort     = '8080'
$ReadyMaxAttempts  = 60
$ReadyDelaySeconds = 2
$PasswordAttempts  = 3

$PasswordPlaceholder = 'CHANGE_ME'

$RepoRoot   = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$EnvFile    = Join-Path $RepoRoot '.env'
$Dockerfile = Join-Path $RepoRoot 'infrastructure\medicore\Dockerfile'
$HealthUrl  = "http://${HostAddress}:${HostPort}/actuator/health"

# Go templates used with "docker inspect". They contain no double quotes on
# purpose (Windows PowerShell 5.1 does not escape embedded quotes for native
# programs); string literals use Go's backtick syntax instead. The env
# templates only ever return variable NAMES or DB_URL, never DB_PASSWORD.
$PortsTemplate    = '{{range $p, $b := .HostConfig.PortBindings}}{{range $b}}{{$p}}={{.HostIp}}:{{.HostPort}};{{end}}{{end}}'
$NetworksTemplate = '{{range $k, $v := .NetworkSettings.Networks}}{{$k}};{{end}}'
$DbUrlTemplate    = '{{range .Config.Env}}{{if eq (index (split . `=`) 0) `DB_URL`}}{{.}}{{end}}{{end}}'
$DbVarsTemplate   = '{{range .Config.Env}}{{$k := index (split . `=`) 0}}{{if or (eq $k `DB_USERNAME`) (eq $k `DB_PASSWORD`)}}{{$k}};{{end}}{{end}}'

$ExpectedPorts  = "$ContainerPort/tcp=${HostAddress}:${HostPort};"
$ExpectedDbUrl  = "DB_URL=$DbUrl"
$ExpectedDbVars = 'DB_USERNAME;DB_PASSWORD;'

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

# Sets (or removes) process environment variables and returns their previous
# values, so they can be restored exactly afterwards.
function Set-ProcessEnv([hashtable]$Values) {
    $previous = @{}
    foreach ($name in $Values.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $Values[$name], 'Process')
    }
    return $previous
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
                "       Run the PostgreSQL start script first; it creates the network and attaches the database:`n" +
                "         .\infrastructure\database\start-postgres.ps1")
        }
        Stop-WithError "Could not inspect network '$NetworkName': $($result.StdErr)"
    }
    Write-Success "Docker network '$NetworkName' exists."
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

# PostgreSQL must be running, attached to the network and accepting connections.
# Only reads its state; never starts, stops or changes it.
function Assert-PostgresAvailable {
    $state = Get-ContainerState $PostgresContainer
    if ($null -eq $state) {
        Stop-WithError ("The PostgreSQL container '$PostgresContainer' does not exist. Start it first:`n" +
            "         .\infrastructure\database\start-postgres.ps1")
    }
    if ($state -ne 'running') {
        Stop-WithError ("The PostgreSQL container '$PostgresContainer' is not running (state: $state). Start it first:`n" +
            "         .\infrastructure\database\start-postgres.ps1")
    }

    $networks = Get-InspectValue $PostgresContainer $NetworksTemplate
    if (@($networks.Split(';')) -notcontains $NetworkName) {
        Stop-WithError ("The PostgreSQL container '$PostgresContainer' is not attached to the network '$NetworkName'.`n" +
            "       Run the PostgreSQL start script; it attaches the database without restarting it:`n" +
            "         .\infrastructure\database\start-postgres.ps1")
    }

    $ready = Invoke-Docker @('exec', $PostgresContainer, 'pg_isready',
        '--host', '127.0.0.1', '--port', $PostgresPort,
        '--username', $DefaultDbUser, '--dbname', $DatabaseName, '--quiet')
    if ($ready.ExitCode -ne 0) {
        Stop-WithError ("PostgreSQL in '$PostgresContainer' is not accepting connections yet.`n" +
            "       Run .\infrastructure\database\start-postgres.ps1 (it waits until the database is ready).")
    }
    Write-Success "PostgreSQL '$PostgresContainer' is running on network '$NetworkName' and accepting connections."
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

# Builds the image only when it does not exist yet.
function Initialize-Image {
    if ($null -ne (Get-ImageId)) {
        Write-Success "Image '$ImageName' exists (not rebuilt; see infrastructure/README.md to rebuild)."
        return
    }

    Write-Info "Image '$ImageName' not found. Building it from infrastructure/medicore/Dockerfile (the first build takes a few minutes)..."
    $exitCode = Invoke-DockerLive @('build', '--file', $Dockerfile, '--tag', $ImageName, $RepoRoot)
    if ($exitCode -ne 0) {
        Stop-WithError "'docker build' failed (exit code $exitCode). See the Docker output above."
    }
    Write-Success "Image '$ImageName' built."
}

# Verifies that an existing container matches the expected configuration.
# Never modifies the container; reports conflicts and stops instead.
function Assert-ContainerConfiguration {
    $image    = Get-InspectValue $ContainerName '{{.Config.Image}}'
    $ports    = Get-InspectValue $ContainerName $PortsTemplate
    $networks = Get-InspectValue $ContainerName $NetworksTemplate
    $dbUrl    = Get-InspectValue $ContainerName $DbUrlTemplate
    $dbVars   = Get-InspectValue $ContainerName $DbVarsTemplate

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
    if ($dbUrl -ne $ExpectedDbUrl) {
        $conflicts += "DB_URL:   expected '$DbUrl', found '$dbUrl'"
    }
    if ($dbVars -ne $ExpectedDbVars) {
        $conflicts += "Database: DB_USERNAME and DB_PASSWORD must both be set (found: '$dbVars')"
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
            "It keeps running the old version until you recreate it:`n" +
            "         docker stop $ContainerName`n" +
            "         docker rm $ContainerName`n" +
            "         .\infrastructure\medicore\start-medicore.ps1")
    }
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
            "       Usually this is MediCore started with 'mvnw spring-boot:run' or from IntelliJ.`n" +
            "       Stop it and run this script again.")
    }
    Write-Success "Host port $HostPort is free."
}

# ---------------------------------------------------------------------------
# Credentials
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

# Reads DB_URL, DB_USERNAME and DB_PASSWORD from the root .env file as plain
# data: the file is never executed or expanded. Format: NAME=value per line;
# blank lines and lines starting with # are ignored; an optional "export "
# prefix and one pair of surrounding quotes ('...' or "...") are removed.
# Other names are ignored. Returns an empty table if the file does not exist.
# Error messages never include values, only line numbers.
function Read-EnvFile([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }

    $lines = @(Get-Content -LiteralPath $Path -Encoding UTF8)
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = ([string]$lines[$index]).Trim().TrimStart([char]0xFEFF)
        if ($line -eq '' -or $line.StartsWith('#')) {
            continue
        }
        if ($line.StartsWith('export ')) {
            $line = $line.Substring(7).TrimStart()
        }

        $separator = $line.IndexOf('=')
        $name = if ($separator -gt 0) { $line.Substring(0, $separator).Trim() } else { '' }
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            Stop-WithError "Line $($index + 1) of $Path is not in the form NAME=value."
        }

        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and $value[0] -eq $value[$value.Length - 1] -and ($value[0] -eq '"' -or $value[0] -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if (@('DB_URL', 'DB_USERNAME', 'DB_PASSWORD') -contains $name) {
            $values[$name] = $value
        }
    }
    return $values
}

# Precedence: a non-empty variable of this PowerShell session wins over .env.
# Returns $null if neither provides a value.
function Select-Setting([string]$Name, [hashtable]$FromFile) {
    $sessionValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($sessionValue)) {
        return [pscustomobject]@{ Value = $sessionValue; Source = 'this PowerShell session' }
    }
    if ($FromFile.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace($FromFile[$Name])) {
        return [pscustomobject]@{ Value = $FromFile[$Name]; Source = '.env' }
    }
    return $null
}

function Test-PlaceholderPassword([string]$Password) {
    return ($Password.Trim() -ceq $PasswordPlaceholder)
}

# Determines DB_URL, DB_USERNAME and DB_PASSWORD for a NEW container:
#   1. variables of this PowerShell session,
#   2. the root .env file,
#   3. default (DB_URL, DB_USERNAME when non-interactive) or a prompt.
# The password prompt hides the input; the password is never printed.
function Resolve-DatabaseSettings {
    $fromFile = Read-EnvFile $EnvFile
    if (Test-Path -LiteralPath $EnvFile -PathType Leaf) {
        Write-Info "Reading database settings from $EnvFile (variables of this session take precedence)."
    }
    else {
        Write-Info "No .env file at $EnvFile; using this session's variables or asking."
    }

    # DB_URL must point to the PostgreSQL container on the Docker network.
    $url = Select-Setting 'DB_URL' $fromFile
    if ($null -eq $url) {
        $url = [pscustomobject]@{ Value = $DbUrl; Source = 'default' }
    }
    if ($url.Value.Trim() -ne $DbUrl) {
        Stop-WithError ("DB_URL from $($url.Source) is '$($url.Value)', but inside Docker MediCore must use:`n" +
            "         $DbUrl`n" +
            "       '$PostgresContainer' is the database container on '$NetworkName'; 127.0.0.1 or localhost`n" +
            "       would point to the MediCore container itself. Fix DB_URL in .env, or remove it from this`n" +
            "       session if it was set for running MediCore outside Docker: Remove-Item Env:DB_URL")
    }
    Write-Info "DB_URL: $DbUrl (from $($url.Source))."

    $userSetting = Select-Setting 'DB_USERNAME' $fromFile
    if ($null -ne $userSetting) {
        $user = $userSetting.Value.Trim()
        Write-Info "DB_USERNAME: '$user' (from $($userSetting.Source))."
    }
    else {
        try {
            $user = Read-Host -Prompt "Database username [$DefaultDbUser]"
        }
        catch {
            # Non-interactive session: fall back to the default username.
            $user = $null
            Write-Info "Using the default database username '$DefaultDbUser'."
        }
        if ([string]::IsNullOrWhiteSpace($user)) {
            $user = $DefaultDbUser
        }
        $user = $user.Trim()
    }

    $passwordSetting = Select-Setting 'DB_PASSWORD' $fromFile
    if ($null -ne $passwordSetting) {
        if (Test-PlaceholderPassword $passwordSetting.Value) {
            Stop-WithError ("DB_PASSWORD from $($passwordSetting.Source) is still the placeholder '$PasswordPlaceholder'. Nothing was created.`n" +
                "       Put your real database password in .env (the one chosen when the database was first created).")
        }
        $password = $passwordSetting.Value
        Write-Info "DB_PASSWORD: set (from $($passwordSetting.Source); not shown)."
    }
    else {
        $password = $null
        for ($attempt = 1; $attempt -le $PasswordAttempts; $attempt++) {
            try {
                $secure = Read-Host -Prompt "Password for database user '$user'" -AsSecureString
            }
            catch {
                Stop-WithError ('The database password is required. Put it in .env (see .env.example) ' +
                    'or run this script in an interactive PowerShell window.')
            }
            $entered = ConvertTo-PlainText $secure
            if ([string]::IsNullOrWhiteSpace($entered)) {
                Write-Warn 'The password must not be empty.'
                continue
            }
            if (Test-PlaceholderPassword $entered) {
                Write-Warn "'$PasswordPlaceholder' is a placeholder, not a password."
                continue
            }
            $password = $entered
            break
        }
        if ($null -eq $password) {
            Stop-WithError "No password entered after $PasswordAttempts attempts. Nothing was created."
        }
    }

    [pscustomobject]@{ User = $user; Password = $password }
}

# .env is only used when the container is created: say so when reusing one.
function Write-EnvFileNotice {
    if (Test-Path -LiteralPath $EnvFile -PathType Leaf) {
        Write-Info ("The existing container keeps the settings it was created with; .env is only read when " +
            "the container is created (see infrastructure/README.md).")
    }
}

# Logs in to PostgreSQL once (SELECT 1) so a wrong password is reported now,
# before a container is created with it. The password reaches psql through
# the docker CLI's environment ("--env PGPASSWORD" without a value).
function Assert-DatabaseCredentials($Credentials) {
    Write-Info "Checking the credentials for database user '$($Credentials.User)'..."
    $previous = Set-ProcessEnv @{ PGPASSWORD = $Credentials.Password }
    try {
        $result = Invoke-Docker @('exec', '--env', 'PGPASSWORD', $PostgresContainer, 'psql',
            '--host', '127.0.0.1', '--port', $PostgresPort,
            '--username', $Credentials.User, '--dbname', $DatabaseName,
            '--no-password', '--tuples-only', '--no-align', '--command', 'SELECT 1')
    }
    finally {
        Set-ProcessEnv $previous | Out-Null
    }

    if ($result.ExitCode -ne 0 -or $result.StdOut -ne '1') {
        if ($result.StdErr -match 'password authentication failed|role .* does not exist') {
            Stop-WithError ("PostgreSQL rejected the username or password for '$($Credentials.User)'. Nothing was created.`n" +
                "       Use the password chosen when the database was first created (see infrastructure/database/README.md).")
        }
        Stop-WithError "Could not log in to PostgreSQL: $($result.StdErr)"
    }
    Write-Success 'Database credentials accepted.'
}

# ---------------------------------------------------------------------------
# Docker actions (build / create / start only - never delete)
# ---------------------------------------------------------------------------
function New-MediCoreContainer($Credentials) {
    Write-Info "Creating container '$ContainerName' from '$ImageName'..."

    # DB_USERNAME and DB_PASSWORD are handed to docker through its process
    # environment ("--env NAME" without a value), so the password never
    # appears in the command line. The previous session values are restored.
    $previous = Set-ProcessEnv @{ DB_USERNAME = $Credentials.User; DB_PASSWORD = $Credentials.Password }
    try {
        $result = Invoke-Docker @('run', '--detach',
            '--name', $ContainerName,
            '--network', $NetworkName,
            '--publish', "${HostAddress}:${HostPort}:${ContainerPort}",
            '--env', "DB_URL=$DbUrl",
            '--env', 'DB_USERNAME',
            '--env', 'DB_PASSWORD',
            $ImageName)
    }
    finally {
        Set-ProcessEnv $previous | Out-Null
    }

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

function Stop-WithContainerLogs([string]$Message) {
    $logs = Invoke-Docker @('logs', '--tail', '25', $ContainerName)
    $text = (@($logs.StdOut, $logs.StdErr) | Where-Object { $_ }) -join "`n"
    Write-Host '----- last lines of the MediCore log -----'
    Write-Host $text
    Write-Host '------------------------------------------'
    Stop-WithError ("$Message`n" +
        "       Common causes: wrong DB_PASSWORD stored in the container, or a database that needs the one-time Flyway baseline.`n" +
        "       Full log: docker logs $ContainerName")
}

# Waits until /actuator/health reports UP (application and database).
function Wait-MediCoreReady {
    $maxSeconds = $ReadyMaxAttempts * $ReadyDelaySeconds
    Write-Info "Waiting for MediCore to report status UP at $HealthUrl (up to $maxSeconds seconds)..."

    for ($attempt = 1; $attempt -le $ReadyMaxAttempts; $attempt++) {
        $state = Get-ContainerState $ContainerName
        if ($state -ne 'running') {
            Stop-WithContainerLogs "Container '$ContainerName' is no longer running (state: '$state')."
        }

        try {
            $response = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 3
            # The actuator answers with application/vnd.spring-boot.actuator.v3+json,
            # which Windows PowerShell 5.1 does not treat as text: .Content is then
            # a byte array and must be decoded before it can be matched.
            $body = $response.Content
            if ($body -is [byte[]]) {
                $body = [System.Text.Encoding]::UTF8.GetString($body)
            }
            if ($response.StatusCode -eq 200 -and $body -match '"status"\s*:\s*"UP"') {
                Write-Success "MediCore is UP (attempt $attempt of $ReadyMaxAttempts)."
                return
            }
        }
        catch {
            # Not started yet, or health is DOWN (HTTP 503): keep waiting.
        }
        Start-Sleep -Seconds $ReadyDelaySeconds
    }

    Stop-WithContainerLogs "MediCore did not report status UP within $maxSeconds seconds."
}

function Show-ConnectionInfo {
    Write-Host ''
    Write-Host 'MediNexus MediCore is running.' -ForegroundColor Green
    Write-Host "  API:        http://localhost:${HostPort}/api/health"
    Write-Host "  Health:     http://localhost:${HostPort}/actuator/health"
    Write-Host "  Container:  $ContainerName (image $ImageName, network $NetworkName)"
    Write-Host "  Database:   $DbUrl"
    Write-Host ''
    Write-Host "  Logs:       docker logs -f $ContainerName"
    Write-Host "  Stop:       docker stop $ContainerName"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
Write-Info "MediNexus MediCore ($ImageName), container '$ContainerName'"
Assert-DockerAvailable
Assert-NetworkExists
Assert-PostgresAvailable
Initialize-Image

$containerState = Get-ContainerState $ContainerName

if ($null -ne $containerState) {
    Write-Info "Container '$ContainerName' already exists (state: $containerState)."
    Assert-ContainerConfiguration
    Write-OutdatedImageWarning
    Write-EnvFileNotice

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
    $credentials = Resolve-DatabaseSettings
    Assert-DatabaseCredentials $credentials
    New-MediCoreContainer $credentials
    $credentials = $null
}

Wait-MediCoreReady
Show-ConnectionInfo
exit 0
