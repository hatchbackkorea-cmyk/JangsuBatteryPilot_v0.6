# GPX Battery Copilot - one-time signing key generator for Windows PowerShell
# IMPORTANT: keep the generated .jks and passwords private. Never commit them to GitHub.
$ErrorActionPreference = "Stop"

$alias = "gpxbattery"
$storePass = Read-Host "Keystore password (remember this)" -AsSecureString
$keyPass = Read-Host "Key password (can be same; remember this)" -AsSecureString
$storePlain = [System.Net.NetworkCredential]::new('', $storePass).Password
$keyPlain = [System.Net.NetworkCredential]::new('', $keyPass).Password
$out = Join-Path $PSScriptRoot "gpxbattery-release.jks"

& keytool -genkeypair -v `
  -keystore $out `
  -storepass $storePlain `
  -keypass $keyPlain `
  -alias $alias `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -dname "CN=GPX Battery Copilot, OU=Private Distribution, O=GPX Battery Copilot, C=KR"

$bytes = [IO.File]::ReadAllBytes($out)
$b64 = [Convert]::ToBase64String($bytes)
$b64Path = Join-Path $PSScriptRoot "ANDROID_KEYSTORE_BASE64.txt"
[IO.File]::WriteAllText($b64Path, $b64)

Write-Host ""
Write-Host "Created: $out"
Write-Host "Created base64 secret: $b64Path"
Write-Host "GitHub Secrets:"
Write-Host "  ANDROID_KEYSTORE_BASE64 = contents of ANDROID_KEYSTORE_BASE64.txt"
Write-Host "  ANDROID_KEYSTORE_PASSWORD = your keystore password"
Write-Host "  ANDROID_KEY_ALIAS = $alias"
Write-Host "  ANDROID_KEY_PASSWORD = your key password"
Write-Host ""
Write-Host "BACK UP the .jks safely. Losing it means installed copies cannot be updated with the same signature."
