# tools/test-resume.ps1

$ErrorActionPreference = 'Stop'

Set-Location (Split-Path $PSScriptRoot -Parent)

New-Item -ItemType Directory -Force `
    target/classes, target/resume-test | Out-Null

$sources = @(
    Get-ChildItem src/main/java -Recurse -Filter *.java |
        ForEach-Object { $_.FullName }
)

& javac --release 21 -d target/classes @sources

if ($LASTEXITCODE -ne 0) {
    throw 'Production compilation failed'
}

& javac --release 21 `
    -cp target/classes `
    -d target/resume-test `
    tools/ResumeStorageSelfTest.java

if ($LASTEXITCODE -ne 0) {
    throw 'Test compilation failed'
}

& java -cp 'target/classes;target/resume-test' ResumeStorageSelfTest

if ($LASTEXITCODE -ne 0) {
    throw 'Resume test failed'
}