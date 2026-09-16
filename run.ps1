$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & mvn compile dependency:copy-dependencies '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Server build failed.' }
    $socketDirectory = Join-Path $PSScriptRoot 'target'
    & java "-Djdk.net.unixdomain.tmpdir=$socketDirectory" -cp 'target/classes;target/dependency/*' server.Main
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error.' }
} finally {
    Pop-Location
}
