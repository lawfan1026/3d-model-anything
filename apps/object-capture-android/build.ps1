param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $taskJava = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $taskJava)) { throw 'Install JDK 17 or set JAVA_HOME to a JDK 17 directory.' }
    if (-not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw 'Set ANDROID_HOME to the Android SDK directory (platform 36 required).' }
    # Use the Windows trust decisions in an isolated build trust store. TLS validation stays enabled.
    New-Item -ItemType Directory -Path '.gradle\build-trust' -Force | Out-Null
    $taskCertBytes = New-Object System.IO.MemoryStream
    Get-ChildItem Cert:\LocalMachine\Root,Cert:\CurrentUser\Root | ForEach-Object { $taskCertBytes.Write($_.RawData,0,$_.RawData.Length) }
    [IO.File]::WriteAllBytes((Join-Path $PSScriptRoot '.gradle\build-trust\roots.der'),$taskCertBytes.ToArray())
    @'
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
public class BuildTrust {
  public static void main(String[] a) throws Exception {
    var store=KeyStore.getInstance("PKCS12"); store.load(null,null); int n=0;
    try(var in=Files.newInputStream(Path.of(a[0]))) {
      for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in)) store.setCertificateEntry("windows-root-"+(n++),cert);
    }
    try(var out=Files.newOutputStream(Path.of(a[1]))) { store.store(out,"build-trust".toCharArray()); }
  }
}
'@ | Set-Content -LiteralPath '.gradle\build-trust\BuildTrust.java'
    & $taskJava '.gradle\build-trust\BuildTrust.java' '.gradle\build-trust\roots.der' '.gradle\build-trust\windows-roots.p12'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create build trust store.' }
    $taskTrust = Join-Path $PSScriptRoot '.gradle\build-trust\windows-roots.p12'
    & '.\gradlew.bat' "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStore=`"$taskTrust`" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=build-trust" @Tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
} finally { Pop-Location }
