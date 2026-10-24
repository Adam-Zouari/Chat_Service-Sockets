[CmdletBinding()]
param(
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$mainOutput = Join-Path $projectRoot 'build/classes/main'
$testOutput = Join-Path $projectRoot 'build/classes/test'

Get-Command javac -CommandType Application -ErrorAction Stop | Out-Null
if ($Test) {
    Get-Command java -CommandType Application -ErrorAction Stop | Out-Null
}

Push-Location -LiteralPath $projectRoot
try {
    $mainSources = @(Get-ChildItem -LiteralPath 'src/main/java' -Recurse -File -Filter '*.java' |
        Sort-Object FullName | ForEach-Object { $_.FullName })
    if ($mainSources.Count -eq 0) {
        throw 'No Java sources found in src/main/java.'
    }
    New-Item -ItemType Directory -Path $mainOutput -Force | Out-Null
    & javac --release 11 -encoding UTF-8 -Xlint:all -d $mainOutput @mainSources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host 'Compiled application classes to build/classes/main.'

    if ($Test) {
        $testSources = @(Get-ChildItem -LiteralPath 'src/test/java' -Recurse -File -Filter '*.java' |
            Sort-Object FullName | ForEach-Object { $_.FullName })
        if ($testSources.Count -eq 0) {
            throw 'No Java tests found in src/test/java.'
        }
        New-Item -ItemType Directory -Path $testOutput -Force | Out-Null
        & javac --release 11 -encoding UTF-8 -Xlint:all -cp $mainOutput -d $testOutput @testSources
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        $testClasspath = $mainOutput + [IO.Path]::PathSeparator + $testOutput
        $testClasses = @(
            'chat.server.ChatServerTest',
            'chat.server.ChatStoreTest',
            'chat.client.ChatConnectionTest',
            'chat.client.ChatClientFormTest',
            'chat.client.ChatViewTest',
            'chat.server.ChatSessionTest'
        )
        foreach ($testClass in $testClasses) {
            Write-Host "Running $testClass"
            & java '-Djava.awt.headless=true' -cp $testClasspath $testClass
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
    }
}
finally {
    Pop-Location
}
