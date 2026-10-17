$ErrorActionPreference='Stop'
$root = 'D:\Code\111\dnsapk'
$bt   = 'C:\AndroidDev\sdk\build-tools\34.0.0'
$jar  = 'C:\AndroidDev\sdk\platforms\android-34\android.jar'
$jdk  = 'C:\AndroidDev\jdk-17\bin'
$libs = "$root\libs"
$out  = "$root\out\v6"

# --- 签名密码：绝不写死在仓库里 ---
# 用环境变量传：  set DNSAPK_KS_PASS=你的密码
# 不设的话会提示输入（输入不回显）
$ksPass = $env:DNSAPK_KS_PASS
if ([string]::IsNullOrEmpty($ksPass)) {
    $sec = Read-Host "输入 keystore 密码（alias: dns）" -AsSecureString
    $ksPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
if ([string]::IsNullOrEmpty($ksPass)) { throw "缺少签名密码：请设置环境变量 DNSAPK_KS_PASS" }

$env:JAVA_HOME = 'C:\AndroidDev\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$cp = "$libs\shizuku-api\classes.jar;$libs\shizuku-provider\classes.jar;$libs\shizuku-shared-classes.jar;$libs\shizuku-aidl-classes.jar;$libs\annotation.jar"
$dexLibs = @(
    "$libs\shizuku-api\classes.jar",
    "$libs\shizuku-provider\classes.jar",
    "$libs\shizuku-shared-classes.jar",
    "$libs\shizuku-aidl-classes.jar"
)

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\gen","$out\classes","$out\dex" | Out-Null

Write-Host "[1/7] aapt2 compile"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] aapt2 link"
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    "$out\res.zip" --java "$out\gen" `
    --min-sdk-version 24 --target-sdk-version 33 `
    --version-code 26 --version-name "3.3.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] javac"
$srcs = @()
$srcs += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath $jar -classpath $cp -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] d8"
$cls = (Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
# 类一多，d8.bat 的命令行会超过 cmd 的 8191 字符上限；先打成 jar 再喂给 d8
$clsJar = "$out\classes-all.jar"
if (Test-Path $clsJar) { Remove-Item $clsJar -Force }
& "$jdk\jar.exe" cf $clsJar -C "$out\classes" . 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --min-api 24 --lib $jar --output "$out\dex" $clsJar $dexLibs 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] pack classes.dex"
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$es = $entry.Open()
$bytes = [System.IO.File]::ReadAllBytes("$out\dex\classes.dex")
$es.Write($bytes, 0, $bytes.Length)
$es.Dispose()
$zip.Dispose()

Write-Host "[6/7] zipalign"
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[7/7] apksigner"
& "$bt\apksigner.bat" sign --ks "$root\dns.keystore" --ks-pass "pass:$ksPass" `
    --ks-key-alias dns --key-pass "pass:$ksPass" `
    --v1-signing-enabled true --v2-signing-enabled true `
    --out "$out\MaomaoDNS-v6.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$bt\apksigner.bat" verify --print-certs "$out\MaomaoDNS-v6.apk" 2>&1 | Select-Object -First 4
$f = Get-Item "$out\MaomaoDNS-v6.apk"
Write-Host ""
Write-Host "OK -> $($f.FullName)  $($f.Length) B  ($([math]::Round($f.Length/1KB,1)) KB)"
Write-Host "dex: $((Get-Item "$out\dex\classes.dex").Length) B"
Write-Host "classes: $($cls.Count)"
