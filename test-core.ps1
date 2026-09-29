$ErrorActionPreference = 'Stop'
$jdk = 'C:\Users\Ben\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2\bin'
Set-Location -LiteralPath $PSScriptRoot
New-Item -ItemType Directory -Force core\build | Out-Null
$sources = @(Get-ChildItem -LiteralPath core\src\org\pxp2\core -Filter '*.java' | ForEach-Object FullName)
$tests = @(Get-ChildItem -LiteralPath core\tests -Filter '*.java' | ForEach-Object FullName)
& "$jdk\javac.exe" -d core\build @sources @tests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$jdk\java.exe" -cp core\build CoreTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$jdk\java.exe" -cp core\build PipelineTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$jdk\java.exe" -cp core\build LibraryTest local-test\test-umd.iso
exit $LASTEXITCODE
