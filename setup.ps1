# One-time setup on Windows: copies the Gradle wrapper from an existing Android Studio project
# and points the build at your Android SDK. Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\setup.ps1
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Get-ChildItem "$env:USERPROFILE\AndroidStudioProjects" -Directory |
  Where-Object { Test-Path "$($_.FullName)\gradle\wrapper\gradle-wrapper.jar" } | Select-Object -First 1
if (-not $src) { throw "No existing project with a Gradle wrapper found. Open this folder in Android Studio instead; it will offer to create one." }
Copy-Item "$($src.FullName)\gradlew", "$($src.FullName)\gradlew.bat" $here -Force
Copy-Item "$($src.FullName)\gradle\wrapper\gradle-wrapper.jar" "$here\gradle\wrapper\" -Force
$sdk = "$env:LOCALAPPDATA\Android\Sdk" -replace '\\','\\' -replace ':','\:'
Set-Content "$here\local.properties" "sdk.dir=$sdk"
Write-Host "Wrapper copied from $($src.Name). Now open this folder in Android Studio and press Run."
