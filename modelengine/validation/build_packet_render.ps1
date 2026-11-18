$ErrorActionPreference = 'Continue' # Java 25 writes native-access warnings to stderr.
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $PSScriptRoot '..\..\..\..\GCBServer\Java\Java_25' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat clean shadowJar test --console=plain --no-daemon 2>&1 | Tee-Object -FilePath '..\..\output\RemoteRagdoll\build-result.log'
exit $LASTEXITCODE
