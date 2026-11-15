$ErrorActionPreference = 'Stop'

$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Arquivo .env não encontrado: $envFile"
}

$javaHomeEntry = Get-Content -LiteralPath $envFile |
    Where-Object { $_ -match '^\s*JAVA_HOME\s*=' } |
    Select-Object -First 1
if (-not $javaHomeEntry) {
    throw 'Defina JAVA_HOME no arquivo .env.'
}

$javaHome = ($javaHomeEntry -replace '^\s*JAVA_HOME\s*=\s*', '').Trim().Trim('"')
$javaExe = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    throw "JDK não encontrado em '$javaHome'. Corrija JAVA_HOME no .env."
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat runClient
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
