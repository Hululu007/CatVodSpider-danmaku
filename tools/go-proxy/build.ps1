param(
    [Parameter(Mandatory=$true)][string]$NdkRoot,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$GoExecutable = 'C:/Program Files/Go/bin/go.exe'
)
$ErrorActionPreference = 'Stop'
$llvm = Join-Path ([IO.Path]::GetFullPath($NdkRoot)) 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$saved = @{}
foreach ($key in @('GOOS','GOARCH','GOARM','CGO_ENABLED','CC','GOTOOLCHAIN')) {
    $saved[$key] = [Environment]::GetEnvironmentVariable($key,'Process')
}
Push-Location $PSScriptRoot
try {
    $env:GOTOOLCHAIN = 'go1.25.5'
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    foreach ($abi in @('arm','arm64')) {
        $env:GOARCH = $abi
        $env:GOARM = if ($abi -eq 'arm') { '7' } else { $null }
        $compiler = if ($abi -eq 'arm') { 'armv7a-linux-androideabi24-clang.cmd' } else { 'aarch64-linux-android24-clang.cmd' }
        $env:CC = Join-Path $llvm $compiler
        if (-not (Test-Path -LiteralPath $env:CC)) { throw "Missing compiler: $compiler" }
        $target = Join-Path $output "goProxy-$abi"
        & $GoExecutable build -trimpath -ldflags='-w -s' -o $target .
        if ($LASTEXITCODE -ne 0) { throw "Go build failed: $abi" }
        & (Join-Path $llvm 'llvm-strip.exe') $target
        if ($LASTEXITCODE -ne 0) { throw "Strip failed: $abi" }
        & $GoExecutable version -m $target
        if ($LASTEXITCODE -ne 0) { throw "Metadata check failed: $abi" }
    }
} finally {
    Pop-Location
    foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
}
Get-FileHash -LiteralPath (Join-Path $output 'goProxy-arm'),(Join-Path $output 'goProxy-arm64') -Algorithm SHA256
