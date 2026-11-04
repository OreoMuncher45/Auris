# Auris for Windows (Android) - portable launcher.
#
# One folder, copied to any Windows 10/11 PC:
#   Start-Auris.cmd        - installs what is missing and opens Auris
#   Uninstall-Auris.cmd    - takes it back off
#   Auris-Wsa.ps1          - the launcher itself
#   WsaFrame.cs            - the border stripper, compiled on the fly
#   auris.config.psd1      - optional settings (APK source, WSA package URLs)
#   Auris.apk              - optional: drop the APK here and it is used as-is
#
# Windows PowerShell 5.1 compatible: no null-coalescing, no ternaries,
# no parallel runspaces.

[CmdletBinding()]
param(
    # A path to an APK. When omitted the launcher looks in this folder, then
    # at the configured URL, then at the newest release on GitHub.
    [string] $ApkPath,

    # Skip the WSA install entirely and only (re)install and open the app.
    [switch] $NoWsaInstall,

    # Print what would happen and stop.
    [switch] $WhatIf,

    # Keep the console open at the end, whatever happened.
    [switch] $KeepOpen,

    # Take Auris back off this PC. The subsystem itself stays: other Android
    # apps may be using it.
    [switch] $Uninstall,

    # Check this folder over and print what it finds, without changing
    # anything: the settings, the border stripper, the APK, and whether the
    # subsystem is on this PC already.
    [switch] $SelfTest
)

$ErrorActionPreference = 'Stop'
$script:LauncherRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:ConfigPath = Join-Path $script:LauncherRoot 'auris.config.psd1'

# --------------------------------------------------------------------------
# Console output
# --------------------------------------------------------------------------

function Write-Step {
    param([string] $Message)
    Write-Host ''
    Write-Host "== $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string] $Message)
    Write-Host "   $Message" -ForegroundColor Green
}

function Write-Note {
    param([string] $Message)
    Write-Host "   $Message" -ForegroundColor DarkGray
}

function Write-Warn {
    param([string] $Message)
    Write-Host "   $Message" -ForegroundColor Yellow
}

function Write-Fail {
    param([string] $Message)
    Write-Host "   $Message" -ForegroundColor Red
}

# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

function Get-Config {
    $defaults = @{
        # Where the launcher gets the Android app from when there is no APK
        # in this folder. Empty means: ask GitHub for the newest release.
        ApkUrl = ''
        # The WSA package (the Android subsystem itself) and its system image.
        # Left empty, the launcher looks in this folder first and then asks
        # GitHub for the newest release of microsoft/WSAPackages.
        WsaPackageUrl = ''
        WsaSystemImageUrl = ''
        # The name WSA gives the window; the border stripper matches on it.
        WindowTitle = 'Auris'
        # Set to $false to leave the window's frame alone.
        StripWindowFrame = $true
    }
    if (-not (Test-Path -LiteralPath $script:ConfigPath)) {
        return $defaults
    }
    $written = Import-PowerShellDataFile -LiteralPath $script:ConfigPath
    foreach ($key in $written.Keys) {
        $defaults[$key] = $written[$key]
    }
    return $defaults
}

# --------------------------------------------------------------------------
# Elevation
# --------------------------------------------------------------------------

function Test-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-Elevated {
    <# Re-runs this script as the administrator, keeping the arguments. #>
    param([string[]] $Arguments)
    $script = $MyInvocation.ScriptName
    $argString = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $script + '"'), $argString
    )
}

# --------------------------------------------------------------------------
# Windows Subsystem for Android
# --------------------------------------------------------------------------

function Get-WsaPackage {
    <# The installed WSA package, or nothing. #>
    return Get-AppxPackage -Name 'MicrosoftCorporationII.MicrosoftSubsystemForAndroid*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Test-WsaFeature {
    param([string] $Name)
    $feature = Get-WindowsOptionalFeature -Online -FeatureName $Name -ErrorAction SilentlyContinue
    return ($null -ne $feature -and $feature.State -eq 'Enabled')
}

function Enable-WsaFeatures {
    Write-Step 'Windows features'
    $needed = @('Microsoft-Windows-Subsystem-for-Android', 'VirtualMachinePlatform')
    $missing = @()
    foreach ($name in $needed) {
        if (Test-WsaFeature -Name $name) {
            Write-Ok "$name is on"
        }
        else {
            $missing += $name
        }
    }
    if ($missing.Count -eq 0) { return $false }

    Write-Note "Turning on: $($missing -join ', ')"
    foreach ($name in $missing) {
        $null = Enable-WindowsOptionalFeature -Online -FeatureName $name -All -NoRestart
    }
    Write-Warn 'Windows needs a restart before the subsystem can start.'
    Write-Note 'Restart, then run this again.'
    return $true
}

function Get-WsaDownloadUrl {
    <# The newest official WSA package and system image, from Microsoft's
       own releases. Returns a hashtable with the two URLs, either of which
       may be an empty string when the release does not carry it. #>
    $result = @{ Package = ''; SystemImage = '' }
    try {
        $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/microsoft/WSAPackages/releases/latest' `
            -Headers @{ 'User-Agent' = 'Auris-Wsa-Launcher' } -UseBasicParsing
        foreach ($asset in $release.assets) {
            $name = $asset.name
            if ($name -like '*.msixbundle') {
                if ($name -match 'system') {
                    $result.SystemImage = $asset.browser_download_url
                }
                else {
                    $result.Package = $asset.browser_download_url
                }
            }
        }
    }
    catch {
        Write-Note "Could not reach GitHub: $($_.Exception.Message)"
    }
    return $result
}

function Install-Wsa {
    Write-Step 'Windows Subsystem for Android'

    $package = Get-WsaPackage
    if ($null -ne $package) {
        Write-Ok "already installed ($($package.Version))"
        return $true
    }
    if ($NoWsaInstall) {
        Write-Fail 'WSA is not installed and -NoWsaInstall was given.'
        return $false
    }

    $config = Get-Config
    $urls = Get-WsaDownloadUrl
    $packageUrl = $config.WsaPackageUrl
    if ([string]::IsNullOrWhiteSpace($packageUrl)) { $packageUrl = $urls.Package }
    $imageUrl = $config.WsaSystemImageUrl
    if ([string]::IsNullOrWhiteSpace($imageUrl)) { $imageUrl = $urls.SystemImage }

    if ([string]::IsNullOrWhiteSpace($packageUrl)) {
        Write-Fail 'No WSA package to install.'
        Write-Note 'Download the WSA package and system image from Microsoft (see README),'
        Write-Note 'put the .msixbundle files in this folder, and run this again.'
        return $false
    }

    $work = Join-Path $env:TEMP 'auris-wsa'
    if (-not (Test-Path -LiteralPath $work)) {
        $null = New-Item -ItemType Directory -Path $work -Force
    }

    $installOrder = @()
    if (-not [string]::IsNullOrWhiteSpace($imageUrl)) { $installOrder += , @($imageUrl, 'system image') }
    $installOrder += , @($packageUrl, 'package')

    foreach ($entry in $installOrder) {
        $url = $entry[0]
        $label = $entry[1]
        $file = Join-Path $work ([IO.Path]::GetFileName(([Uri] $url).AbsolutePath))
        Write-Note "fetching the $label"
        try {
            Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
        }
        catch {
            Write-Fail "could not download the $label : $($_.Exception.Message)"
            return $false
        }
        Write-Note "installing the $label"
        try {
            Add-AppxPackage -Path $file -ForceUpdateFromAnyVersion
            Write-Ok "$label installed"
        }
        catch {
            Write-Fail "could not install the $label : $($_.Exception.Message)"
            Write-Note 'If this says the package failed validation, the file may be a'
            Write-Note 'system image rather than the subsystem: check the names.'
            return $false
        }
    }
    return $true
}

# --------------------------------------------------------------------------
# The app itself
# --------------------------------------------------------------------------

function Get-AurisApk {
    param([string] $ExplicitPath)
    $config = Get-Config

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        if (Test-Path -LiteralPath $ExplicitPath) { return (Resolve-Path -LiteralPath $ExplicitPath).Path }
        Write-Fail "no APK at $ExplicitPath"
        return $null
    }

    $inFolder = Get-ChildItem -LiteralPath $script:LauncherRoot -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $inFolder) {
        Write-Ok "using $($inFolder.Name)"
        return $inFolder.FullName
    }

    if (-not [string]::IsNullOrWhiteSpace($config.ApkUrl)) {
        $work = Join-Path $env:TEMP 'auris-wsa'
        if (-not (Test-Path -LiteralPath $work)) {
            $null = New-Item -ItemType Directory -Path $work -Force
        }
        $file = Join-Path $work 'Auris.apk'
        Write-Note 'fetching Auris from the configured address'
        try {
            Invoke-WebRequest -Uri $config.ApkUrl -OutFile $file -UseBasicParsing
            return $file
        }
        catch {
            Write-Fail "could not fetch the APK : $($_.Exception.Message)"
            return $null
        }
    }

    Write-Note 'looking for the newest release on GitHub'
    try {
        $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/OreoMuncher45/Auris/releases/latest' `
            -Headers @{ 'User-Agent' = 'Auris-Wsa-Launcher' } -UseBasicParsing
        $asset = $release.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
        if ($null -eq $asset) {
            Write-Fail 'the newest release has no APK in it.'
            Write-Note 'Put Auris.apk in this folder and run this again.'
            return $null
        }
        $work = Join-Path $env:TEMP 'auris-wsa'
        if (-not (Test-Path -LiteralPath $work)) {
            $null = New-Item -ItemType Directory -Path $work -Force
        }
        $file = Join-Path $work $asset.name
        Write-Note "fetching $($asset.name)"
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $file -UseBasicParsing
        Write-Ok "using $($asset.name)"
        return $file
    }
    catch {
        Write-Fail "could not reach GitHub : $($_.Exception.Message)"
        Write-Note 'Put Auris.apk in this folder and run this again.'
        return $null
    }
}

function Install-Auris {
    param([string] $Apk)
    Write-Step 'Auris'
    $installed = Get-AppxPackage -Name 'com.music.bitchord*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    try {
        if ($null -ne $installed) {
            Write-Note 'updating the copy already installed'
            Add-AppxPackage -Path $Apk -ForceUpdateFromAnyVersion -ForceApplicationShutdown
        }
        else {
            Add-AppxPackage -Path $Apk
        }
    }
    catch {
        # An older build with a different signature has to go first.
        if ($null -ne $installed) {
            Write-Note 'the installed copy will not update; removing it first'
            try {
                Remove-AppxPackage -Package $installed.PackageFullName -ErrorAction Stop
                Add-AppxPackage -Path $Apk
            }
            catch {
                Write-Fail "could not install Auris : $($_.Exception.Message)"
                return $false
            }
        }
        else {
            Write-Fail "could not install Auris : $($_.Exception.Message)"
            return $false
        }
    }
    Write-Ok 'installed'
    return $true
}

function Get-AurisLaunchInfo {
    <# The package family name and the application id, so Windows can be
       asked to open the app by name rather than by guesswork. #>
    $package = Get-AppxPackage -Name 'com.music.bitchord*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $package) { return $null }
    $appId = $null
    try {
        $manifest = Get-AppxPackageManifest -Package $package.PackageFullName
        $app = $manifest.Package.Applications.Application | Select-Object -First 1
        if ($null -ne $app) { $appId = $app.Id }
    }
    catch {
        Write-Note "could not read the manifest: $($_.Exception.Message)"
    }
    if ([string]::IsNullOrWhiteSpace($appId)) { $appId = 'App' }
    return @{
        FamilyName = $package.PackageFamilyName
        AppId = $appId
    }
}

function Start-Auris {
    Write-Step 'Opening Auris'
    $info = Get-AurisLaunchInfo
    if ($null -eq $info) {
        Write-Fail 'Auris is not installed.'
        return $false
    }
    $target = "shell:AppsFolder\$($info.FamilyName)!$($info.AppId)"
    Write-Note $target
    Start-Process -FilePath 'explorer.exe' -ArgumentList $target
    return $true
}

# --------------------------------------------------------------------------
# The window frame
# --------------------------------------------------------------------------

function Start-FrameStripper {
    <# WSA draws its own window around the Android app: a caption bar and a
       visible outline. The app draws its own chrome, so the frame is taken
       away and the outline made invisible, while the window stays resizable
       because the resize border itself is kept. #>
    $config = Get-Config
    if (-not $config.StripWindowFrame) {
        Write-Note 'leaving the window frame alone'
        return
    }
    $source = Join-Path $script:LauncherRoot 'WsaFrame.cs'
    if (-not (Test-Path -LiteralPath $source)) {
        Write-Note 'WsaFrame.cs is missing; the window keeps its frame.'
        return
    }
    Write-Step 'The window'
    try {
        Add-Type -Path $source -ErrorAction Stop
        [Auris.WsaFrame]::Start($config.WindowTitle)
        Write-Ok 'frame will be taken off as soon as the window appears'
    }
    catch {
        Write-Note "could not strip the frame: $($_.Exception.Message)"
        Write-Note 'Auris will still open; the frame will just stay.'
    }
}

# --------------------------------------------------------------------------
# The run
# --------------------------------------------------------------------------

function Invoke-SelfTest {
    Write-Step 'This folder'
    $config = Get-Config
    Write-Ok 'the settings load'
    foreach ($key in @('WindowTitle', 'StripWindowFrame')) {
        Write-Note ("{0} = {1}" -f $key, $config[$key])
    }

    $source = Join-Path $script:LauncherRoot 'WsaFrame.cs'
    if (Test-Path -LiteralPath $source) {
        try {
            Add-Type -Path $source -ErrorAction Stop
            Write-Ok 'the border stripper compiles'
        }
        catch {
            Write-Fail "the border stripper does not compile: $($_.Exception.Message)"
        }
    }
    else {
        Write-Fail 'WsaFrame.cs is missing'
    }

    $apk = Get-ChildItem -LiteralPath $script:LauncherRoot -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $apk) {
        Write-Ok "the APK is here: $($apk.Name) ($([math]::Round($apk.Length / 1MB)) MB)"
    }
    else {
        Write-Note 'no APK in this folder; one will be fetched when the launcher runs'
    }

    if ($env:OS -eq 'Windows_NT') {
        Write-Step 'This PC'
        $package = Get-WsaPackage
        if ($null -ne $package) {
            Write-Ok "the subsystem is installed ($($package.Version))"
        }
        else {
            Write-Note 'the subsystem is not installed yet'
        }
        foreach ($name in @('Microsoft-Windows-Subsystem-for-Android', 'VirtualMachinePlatform')) {
            if (Test-WsaFeature -Name $name) { Write-Ok "$name is on" } else { Write-Note "$name is off" }
        }
    }
    else {
        Write-Note 'not Windows, so the PC side was not looked at'
    }
    return $true
}

function Remove-Auris {
    Write-Step 'Removing Auris'
    $package = Get-AppxPackage -Name 'com.music.bitchord*' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $package) {
        Write-Ok 'Auris is not installed'
        return $true
    }
    try {
        Remove-AppxPackage -Package $package.PackageFullName
        Write-Ok 'removed'
        return $true
    }
    catch {
        Write-Fail "could not remove Auris : $($_.Exception.Message)"
        return $false
    }
}

function Invoke-Launcher {
    Write-Host ''
    Write-Host 'Auris for Windows (Android)' -ForegroundColor White
    Write-Host 'The Android app, on Windows, in a window.' -ForegroundColor DarkGray

    if (-not (Test-Admin)) {
        Write-Warn 'This has to run as the administrator to turn Windows features on.'
        Write-Note 'A window will ask. Say yes, and this continues there.'
        $arguments = @()
        if ($WhatIf) { $arguments += '-WhatIf' }
        if ($NoWsaInstall) { $arguments += '-NoWsaInstall' }
        if ($KeepOpen) { $arguments += '-KeepOpen' }
        if ($Uninstall) { $arguments += '-Uninstall' }
        if ($SelfTest) { $arguments += '-SelfTest' }
        if (-not [string]::IsNullOrWhiteSpace($ApkPath)) { $arguments += @('-ApkPath', $ApkPath) }
        Invoke-Elevated -Arguments $arguments
        return $true
    }

    if ($SelfTest) {
        return (Invoke-SelfTest)
    }

    if ($Uninstall) {
        if ($WhatIf) {
            Write-Step 'What would happen'
            Write-Note 'Auris would be removed from this PC.'
            return $true
        }
        return (Remove-Auris)
    }

    if ($WhatIf) {
        Write-Step 'What would happen'
        Write-Note 'Windows features for the subsystem would be checked.'
        Write-Note 'The subsystem would be installed if it is missing.'
        Write-Note 'Auris would be installed or updated.'
        Write-Note 'Auris would be opened, and its window frame taken off.'
        return $true
    }

    if (Enable-WsaFeatures) {
        Write-Note 'Restart Windows, then run this again.'
        return $true
    }
    if (-not (Install-Wsa)) { return $false }

    $apk = Get-AurisApk -ExplicitPath $ApkPath
    if ($null -eq $apk) { return $false }
    if (-not (Install-Auris -Apk $apk)) { return $false }

    Start-FrameStripper
    if (-not (Start-Auris)) { return $false }

    Write-Host ''
    Write-Ok 'Auris is open.'
    Write-Note 'This window can be closed; Auris keeps running.'
    return $true
}

try {
    $ok = Invoke-Launcher
}
catch {
    Write-Host ''
    Write-Fail $_.Exception.Message
    Write-Note $_.ScriptStackTrace
    $ok = $false
}

if ($KeepOpen) {
    Write-Host ''
    Read-Host 'Press Enter to close'
}
elseif (-not $ok) {
    Write-Host ''
    Read-Host 'Press Enter to close'
}
exit $(if ($ok) { 0 } else { 1 })
