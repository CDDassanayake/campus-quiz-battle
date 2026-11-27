param(
    [switch]$Mock,
    [switch]$Smoke,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$moduleRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleRoot
function Get-IncompleteIdeClasses {
    # Eclipse/JDT can emit executable error stubs into Maven's output directory.
    # Their fresh timestamps can otherwise make Maven skip the necessary compile.
    foreach ($relativeOutput in @('target/classes','target/test-classes')) {
        $generatedRoot = [IO.Path]::GetFullPath((Join-Path $moduleRoot $relativeOutput))
        $targetPrefix = [IO.Path]::GetFullPath((Join-Path $moduleRoot 'target')) + [IO.Path]::DirectorySeparatorChar
        if (-not $generatedRoot.StartsWith($targetPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid generated-class directory.' }
        if (-not (Test-Path -LiteralPath $generatedRoot)) { continue }
        Get-ChildItem -LiteralPath $generatedRoot -Filter '*.class' -Recurse | Where-Object {
            [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($_.FullName)).Contains('Unresolved compilation problem')
        }
    }
}
if ($Mock -and $Smoke) { throw 'Choose either -Mock or -Smoke.' }
Push-Location -LiteralPath $repoRoot
try {
    if (-not $SkipBuild) {
        $incompleteClasses = @(Get-IncompleteIdeClasses)
        if ($incompleteClasses.Count -gt 0) {
            Write-Output 'Rebuilding generated classes from an incomplete IDE compilation.'
            foreach ($incompleteClass in $incompleteClasses) { Remove-Item -LiteralPath $incompleteClass.FullName }
        }
        # Compile production and test launchers. Acceptance tests are a separate command.
        & mvn -q -pl m2-tcp-client -am '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'The GUI build failed.' }
    }
    if (@(Get-IncompleteIdeClasses).Count -gt 0) {
        throw 'The IDE produced incomplete generated classes. Pause automatic IDE compilation and rerun without -SkipBuild.'
    }
    $modulePath = Join-Path $moduleRoot 'target/javafx'
    if (-not (Test-Path -LiteralPath $modulePath)) { throw 'JavaFX runtime files are missing. Run without -SkipBuild.' }
    $classPaths = @((Join-Path $moduleRoot 'target/classes'), (Join-Path $repoRoot 'common/target/classes'))
    $mainClass = 'com.quizbattle.client.ui.GuiLauncher'
    if ($Mock -or $Smoke) { $classPaths += Join-Path $moduleRoot 'target/test-classes' }
    if ($Mock) { $mainClass = 'com.quizbattle.client.MockGuiLauncher' }
    if ($Smoke) { $mainClass = 'com.quizbattle.client.GuiSmokeLauncher' }
    & java --module-path $modulePath --add-modules javafx.controls,javafx.fxml -cp ($classPaths -join [IO.Path]::PathSeparator) $mainClass
    if ($LASTEXITCODE -ne 0) { throw "GUI process exited with code $LASTEXITCODE." }
}
finally { Pop-Location }
