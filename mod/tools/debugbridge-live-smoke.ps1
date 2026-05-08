param(
    [string] $DebugBridgeRoot = (Resolve-Path "$PSScriptRoot\..").Path,
    [string] $RenderModRoot = "C:\Users\ttski\Projects\IdeaProjects\minecraft-vulkan-improvement-mod-26.2-dev",
    [switch] $SkipBuild,
    [switch] $StartClient
)

$ErrorActionPreference = "Stop"

function Write-Step([string] $Message) {
    Write-Host "[debugbridge-live-smoke] $Message"
}

$gradle = Join-Path $DebugBridgeRoot "gradlew.bat"
$renderGradle = Join-Path $RenderModRoot "gradlew.bat"
$fabricJarGlob = Join-Path $DebugBridgeRoot "fabric-26.2-dev\build\libs\debugbridge-26.2-dev-*.jar"
$renderMods = Join-Path $RenderModRoot "run\mods"

if (-not $SkipBuild) {
    Write-Step "Building DebugBridge artifacts"
    $gradleTasks = @(":core:test", ":fabric-26.2-dev:jar")
    & $gradle @gradleTasks "--console=plain"
    if ($LASTEXITCODE -ne 0) {
        throw "DebugBridge Gradle verification failed with exit code $LASTEXITCODE"
    }
}

$fabricJar = Get-ChildItem -Path $fabricJarGlob |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $fabricJar) {
    throw "Could not find built debugbridge-26.2-dev jar at $fabricJarGlob"
}

New-Item -ItemType Directory -Force -Path $renderMods | Out-Null
Get-ChildItem -Path (Join-Path $renderMods "debugbridge-26.2-*.jar") -ErrorAction SilentlyContinue |
    Remove-Item -Force

$destination = Join-Path $renderMods $fabricJar.Name
Copy-Item -Path $fabricJar.FullName -Destination $destination -Force
Write-Step "Copied $($fabricJar.Name) to $destination"

if ($StartClient) {
    if (-not (Test-Path $renderGradle)) {
        throw "Render mod Gradle wrapper not found: $renderGradle"
    }
    Write-Step "Starting Minecraft client from render mod workspace"
    Push-Location $RenderModRoot
    try {
        & $renderGradle "runClient" "--console=plain"
    } finally {
        Pop-Location
    }
}

Write-Step "Next MCP calls: mc_snapshot, mc_screenshot, mc_execute. See docs/live-smoke.md."
