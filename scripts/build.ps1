param(
    [string[]]$Tasks = @(':app:assembleDebug'),
    [string]$Abi = 'arm64-v8a',
    [string]$SdkPath = $env:ANDROID_SDK_ROOT
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if ([string]::IsNullOrWhiteSpace($SdkPath)) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath $SdkPath)) { throw 'Pass -SdkPath pointing to an installed Android SDK.' }
$gradleRoot = Join-Path $projectRoot 'build/toolchains/gradle/gradle-8.13'
if (-not (Test-Path -LiteralPath $gradleRoot)) {
    python scripts/bootstrap_toolchains.py
    if ($LASTEXITCODE -ne 0) { throw 'Toolchain bootstrap failed.' }
}
$sdkNormalized = $SdkPath.Replace('\', '/')
$cmakeNormalized = (Join-Path $projectRoot 'build/toolchains/cmake').Replace('\', '/')
Set-Content -LiteralPath local.properties -Encoding ASCII -Value @("sdk.dir=$sdkNormalized", "cmake.dir=$cmakeNormalized")
$javaPath = (Get-Command java.exe).Source
& $javaPath "-javaagent:$gradleRoot/lib/agents/gradle-instrumentation-agent-8.13.jar" `
    -classpath "$gradleRoot/lib/gradle-gradle-cli-main-8.13.jar" org.gradle.launcher.GradleMain `
    --gradle-user-home "$projectRoot/build/gradle-home" --no-daemon --console plain "-PtargetAbi=$Abi" @Tasks
exit $LASTEXITCODE
