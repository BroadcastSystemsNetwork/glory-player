$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Path "C:\Android\build-tools" -Force | Out-Null
curl.exe -L --retry 5 "https://dl.google.com/android/repository/build-tools_r35_windows.zip" -o "$env:TEMP\bt35.zip"
Expand-Archive -Path "$env:TEMP\bt35.zip" -DestinationPath "$env:TEMP\bt35_out" -Force
$dir = Get-ChildItem "$env:TEMP\bt35_out" -Directory | Select-Object -First 1
Move-Item $dir.FullName "C:\Android\build-tools\35.0.0" -Force
cd C:\GloryPlayerApp\android
.\gradlew.bat assembleDebug