$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)

$previousPassword = $env:SYNCSHARE_TLS_PASSWORD
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())

try {
    mvn -q compile
    if ($LASTEXITCODE -ne 0) { throw 'Maven compilation failed' }

    New-Item -ItemType Directory -Force target/resume-test, $tempDir | Out-Null

    javac --release 21 -cp target/classes -d target/resume-test tools/ResumeStorageSelfTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Self-test compilation failed' }

    $env:SYNCSHARE_TLS_PASSWORD = [guid]::NewGuid().ToString('N')
    $identity = Join-Path $tempDir 'identity.p12'
    $trust = Join-Path $tempDir 'trusted-peers.p12'
    $certificate = Join-Path $tempDir 'test.cer'

    keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=SyncShare-Test' -storetype PKCS12 -keystore $identity -storepass:env SYNCSHARE_TLS_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Test identity generation failed' }

    keytool -exportcert -alias test -keystore $identity -storepass:env SYNCSHARE_TLS_PASSWORD -file $certificate
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate export failed' }

    keytool -importcert -noprompt -alias test -file $certificate -keystore $trust -storetype PKCS12 -storepass:env SYNCSHARE_TLS_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Test truststore creation failed' }

    java "-Dsyncshare.keyStore=$identity" "-Dsyncshare.trustStore=$trust" -cp 'target/classes;target/resume-test' ResumeStorageSelfTest $certificate
    if ($LASTEXITCODE -ne 0) { throw 'TLS resume test failed' }
}
finally {
    $env:SYNCSHARE_TLS_PASSWORD = $previousPassword
    Pop-Location
    if (Test-Path $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}