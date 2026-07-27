# Builds (if needed) and runs the GitGate server in the foreground.
# Stop it with Ctrl+C. Logs print to this console.
$ErrorActionPreference = "Stop"

# Auto-detect JDK 21.
$jdk = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-21*" -and (Test-Path "$($_.FullName)\bin\java.exe") } |
    Select-Object -First 1
if (-not $jdk) { throw "JDK 21 not found under C:\Program Files\Java" }
$env:JAVA_HOME = $jdk.FullName
Write-Host "Using JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Cyan

$root = Split-Path $PSScriptRoot -Parent
$jar  = Join-Path $root "target\gitgate-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    throw "Jar not found. Build first:  mvn -f `"$root\pom.xml`" clean package"
}

Write-Host "Starting GitGate on http://localhost:8080  (Ctrl+C to stop)" -ForegroundColor Green
& "$env:JAVA_HOME\bin\java.exe" -jar $jar
