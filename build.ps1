$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Users\Ben\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
$env:ANDROID_HOME = 'C:\Users\Ben\AppData\Local\Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location -LiteralPath $PSScriptRoot
$ErrorActionPreference = 'Continue'
& 'C:\Users\Ben\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat' :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain --max-workers=4
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item -LiteralPath app\build\outputs\apk\release\app-release.apk -Destination dist\PXP2-fresh.apk
Copy-Item -LiteralPath app\build\outputs\apk\debug\app-debug.apk -Destination dist\PXP2-fresh-debug.apk
