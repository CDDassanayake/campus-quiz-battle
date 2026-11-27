param([string]$Revision = 'origin/develop', [switch]$SkipBuild, [switch]$UseWorkingTree)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleRoot
$priorKeystorePassword = $env:QB_KEYSTORE_PASSWORD
$priorTokenSecret = $env:QB_TOKEN_SECRET
$priorTlsPort = $env:QB_TLS_AUTH_PORT
$priorKeystorePath = $env:QB_SECURITY_KEYSTORE_PATH
Push-Location -LiteralPath $repoRoot
try {
    if (-not $SkipBuild) {
        $buildModules = if ($UseWorkingTree) { 'm2-tcp-client,m5-security' } else { 'm2-tcp-client' }
        & mvn -q -pl $buildModules -am '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'M2 probe build failed.' }
    }
    $verifiedRevision = (& git rev-parse --verify ($Revision + '^{commit}')).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'The requested local Git revision is unavailable. Fetch remote refs separately.' }
    $probeRoot = Join-Path $moduleRoot ('target/real-m5/' + [Guid]::NewGuid().ToString('N'))
    $sourceRoot = Join-Path $probeRoot 'source'
    $classesRoot = Join-Path $probeRoot 'classes'
    New-Item -ItemType Directory -Path $sourceRoot,$classesRoot | Out-Null
    $sourcePaths = @()
    if ($UseWorkingTree) {
        & git diff --quiet $verifiedRevision -- m5-security/src/main/java
        if ($LASTEXITCODE -ne 0) { throw 'M5 working-tree source differs from the recorded revision. Review those changes before probing it.' }
        $classesRoot = Join-Path $repoRoot 'm5-security/target/classes'
        foreach ($className in @('HmacTokenService','TlsAuthServer','TlsContextFactory','TlsSecureLoginClient','UserStore')) {
            if (-not (Test-Path -LiteralPath (Join-Path $classesRoot ('com/quizbattle/security/' + $className + '.class')))) {
                throw 'Compiled real M5 classes are missing. Run without -SkipBuild.'
            }
        }
    } else {
        foreach ($className in @('HmacTokenService','TlsAuthServer','TlsContextFactory','TlsSecureLoginClient','UserStore')) {
            $sourcePath = Join-Path $sourceRoot ($className + '.java')
            $sourceText = & git show ($verifiedRevision + ':m5-security/src/main/java/com/quizbattle/security/' + $className + '.java')
            if ($LASTEXITCODE -ne 0) { throw 'Actual M5 source is missing at the requested revision.' }
            Set-Content -LiteralPath $sourcePath -Value $sourceText -Encoding utf8
            $sourcePaths += $sourcePath
        }
        & javac --release 17 -encoding UTF-8 -cp 'common/target/classes' -d $classesRoot @sourcePaths
        if ($LASTEXITCODE -ne 0) { throw 'Actual M5 snapshot did not compile against current common contracts.' }
    }
    $exampleClasspath = @('m2-tcp-client/target/classes','common/target/classes',$classesRoot) -join [IO.Path]::PathSeparator
    $exampleClasses = Join-Path $probeRoot 'example-classes'
    New-Item -ItemType Directory -Path $exampleClasses | Out-Null
    & javac --release 17 -encoding UTF-8 -cp $exampleClasspath -d $exampleClasses 'm2-tcp-client/examples/M5ManualProvider.java'
    if ($LASTEXITCODE -ne 0) { throw 'Proposed app wiring example did not compile against actual constructors.' }
    $env:QB_KEYSTORE_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:QB_TOKEN_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
    foreach ($identity in @('server','unrelated')) {
        $keystorePath = Join-Path $probeRoot ($identity + '.p12')
        $certificatePath = Join-Path $probeRoot ($identity + '.pem')
        & keytool -genkeypair -alias quizprobe -keyalg RSA -keysize 2048 -validity 2 -storetype PKCS12 -keystore $keystorePath '-storepass:env' QB_KEYSTORE_PASSWORD -dname 'CN=localhost' -ext 'SAN=dns:localhost' -noprompt 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Temporary test certificate generation failed.' }
        & keytool -exportcert -rfc -alias quizprobe -keystore $keystorePath '-storepass:env' QB_KEYSTORE_PASSWORD -file $certificatePath 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Temporary public certificate export failed.' }
    }
    $propertiesPath = Join-Path $probeRoot 'server.properties'
    $env:QB_TLS_AUTH_PORT = '0'
    $env:QB_SECURITY_KEYSTORE_PATH = Join-Path $probeRoot 'server.p12'
    @('tls.auth.port=0', ('security.keystore.path=' + (Join-Path $probeRoot 'server.p12').Replace('\','/'))) | Set-Content -LiteralPath $propertiesPath
    $classpath = @('m2-tcp-client/target/test-classes','m2-tcp-client/target/classes','common/target/classes') -join [IO.Path]::PathSeparator
    $output = & java -cp $classpath com.quizbattle.client.RealSecurityProbe $classesRoot $propertiesPath (Join-Path $probeRoot 'server.pem') (Join-Path $probeRoot 'unrelated.pem') 2>&1
    $probeExit = $LASTEXITCODE
    $output | Tee-Object -FilePath (Join-Path $probeRoot 'verification.txt')
    ('Source revision: ' + $verifiedRevision) | Add-Content -LiteralPath (Join-Path $probeRoot 'verification.txt')
    ('M5 implementation: ' + $(if ($UseWorkingTree) { 'merged module classes from m5-security/target/classes' } else { 'isolated source export' })) | Add-Content -LiteralPath (Join-Path $probeRoot 'verification.txt')
    Write-Output ('Probe artifacts: ' + $probeRoot)
    if ($probeExit -ne 0) { throw 'Actual M5 TLS probe failed.' }
} finally {
    $env:QB_KEYSTORE_PASSWORD = $priorKeystorePassword
    $env:QB_TOKEN_SECRET = $priorTokenSecret
    $env:QB_TLS_AUTH_PORT = $priorTlsPort
    $env:QB_SECURITY_KEYSTORE_PATH = $priorKeystorePath
    Pop-Location
}
