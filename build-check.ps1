$ErrorActionPreference = 'Stop'
# Compile-only check: aapt2 link (for R.java) + javac. No signing, no password needed.
$root = 'D:\Code\111\dnsapk'
$bt   = 'C:\AndroidDev\sdk\build-tools\34.0.0'
$jar  = 'C:\AndroidDev\sdk\platforms\android-34\android.jar'
$jdk  = 'C:\AndroidDev\jdk-17\bin'
$libs = "$root\libs"
$out  = "$root\out\check"

$env:JAVA_HOME = 'C:\AndroidDev\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$cp = "$libs\shizuku-api\classes.jar;$libs\shizuku-provider\classes.jar;$libs\shizuku-shared-classes.jar;$libs\shizuku-aidl-classes.jar;$libs\annotation.jar"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\gen","$out\classes" | Out-Null

Write-Host "[1/3] aapt2 compile"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/3] aapt2 link"
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    "$out\res.zip" --java "$out\gen" `
    --min-sdk-version 24 --target-sdk-version 33 `
    --version-code 34 --version-name "3.5.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/3] javac"
$srcs = @()
$srcs += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath $jar -classpath $cp -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$n = (Get-ChildItem "$out\classes" -Recurse -Filter *.class).Count
Write-Host ""
Write-Host "COMPILE OK  ->  $n classes"
