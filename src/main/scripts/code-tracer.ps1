#Requires -Version 7.4
# Keep native argument forwarding predictable without changing the caller's preferences.
$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'
$PSNativeCommandUseErrorActionPreference = $false

try {
    # Resolve a file symlink while retaining the caller's working directory.
    $scriptFile = Get-Item -LiteralPath $PSCommandPath
    if ($null -ne $scriptFile.LinkTarget) {
        $scriptFile = $scriptFile.ResolveLinkTarget($true)
    }
    $jarDirectory = $scriptFile.DirectoryName
    $jarHint = 'keep the launcher beside code-tracer.jar or set CODE_TRACER_JAR_DIR'
    if (-not [string]::IsNullOrEmpty($env:CODE_TRACER_JAR_DIR)) {
        $jarHint = 'correct CODE_TRACER_JAR_DIR'
        # .NET path handling keeps a stored tilde or variable reference literal.
        try {
            $jarDirectory = [IO.Path]::GetFullPath($env:CODE_TRACER_JAR_DIR, (Get-Location).ProviderPath)
        } catch {
            throw "Invalid CODE_TRACER_JAR_DIR '$env:CODE_TRACER_JAR_DIR': $($_.Exception.Message)"
        }
    }
    $jarPath = [IO.Path]::Combine($jarDirectory, 'code-tracer.jar')
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Cannot find $jarPath; $jarHint."
    }
    try {
        $jarStream = [System.IO.File]::OpenRead($jarPath)
        $jarStream.Dispose()
    } catch {
        throw "Cannot read $jarPath; $jarHint. $($_.Exception.Message)"
    }

    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) {
        $javaCommand = Join-Path $env:JAVA_HOME "bin/$javaName"
        if (-not (Test-Path -LiteralPath $javaCommand -PathType Leaf)) {
            throw "JAVA_HOME does not select a Java executable: $javaCommand"
        }
    } else {
        $javaApplication = Get-Command $javaName -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $javaApplication) {
            throw 'Java was not found; set JAVA_HOME or add a full JDK 21 or later to PATH.'
        }
        $javaCommand = $javaApplication.Source
    }

    # Windows has no Bash-style exec replacement; wait for Java and return its exit code.
    & $javaCommand '-jar' $jarPath @args
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine("code-tracer: $($_.Exception.Message)")
    exit 1
}
