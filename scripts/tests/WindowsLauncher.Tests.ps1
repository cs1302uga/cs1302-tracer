#Requires -Version 7.4
BeforeAll {
    $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) "tracer launchers $([guid]::NewGuid())"
    # Java canonicalizes macOS's /var alias when reporting its working directory.
    if ($IsMacOS -and $testRoot.StartsWith('/var/')) { $testRoot = '/private' + $testRoot }
    $installDir = Join-Path $testRoot 'installed application'
    $callerDir = Join-Path $testRoot 'caller directory'
    New-Item -ItemType Directory -Path $installDir, $callerDir | Out-Null
    Copy-Item "$repoRoot/src/main/scripts/code-tracer.ps1" $installDir
    Copy-Item "$repoRoot/src/main/scripts/code-tracer.cmd" $installDir
    Copy-Item "$repoRoot/src/main/scripts/code-tracer.sh" "$installDir/code-tracer"
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javaPath = (Get-Command $javaName -CommandType Application | Select-Object -First 1).Source
    $selectedJdk = Split-Path (Split-Path $javaPath -Parent) -Parent
    if ($env:JAVA_HOME) { $selectedJdk = $env:JAVA_HOME }
    $pwshPath = (Get-Process -Id $PID).Path
    @'
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public class LauncherProbe {
    public static void main(String[] args) {
        System.out.println(Base64.getEncoder().encodeToString(
            System.getProperty("user.dir").getBytes(StandardCharsets.UTF_8)));
        System.out.println(Base64.getEncoder().encodeToString(
            System.getProperty("java.home").getBytes(StandardCharsets.UTF_8)));
        for (String arg : args) {
            System.out.println(Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        }
        System.err.print("probe stderr");
        System.exit(23);
    }
}
'@ | Set-Content -LiteralPath "$testRoot/LauncherProbe.java" -Encoding utf8
    & "$selectedJdk/bin/javac" -d $testRoot "$testRoot/LauncherProbe.java"
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
    & "$selectedJdk/bin/jar" --create --file "$installDir/code-tracer.jar" --main-class LauncherProbe -C $testRoot LauncherProbe.class
    if ($LASTEXITCODE -ne 0) { throw 'Probe packaging failed' }

    function Invoke-LauncherProbe {
        param(
            [string] $Kind,
            [string[]] $Arguments = @(),
            [string] $JavaHome = $selectedJdk,
            [string] $SearchPath = $env:PATH,
            [string] $LauncherDirectory = $installDir,
            [string] $CommandName = 'code-tracer.cmd',
            [object] $JarDirectory = $null
        )
        $start = [Diagnostics.ProcessStartInfo]::new()
        $start.UseShellExecute = $false
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.WorkingDirectory = $callerDir
        $start.Environment['JAVA_HOME'] = $JavaHome
        $start.Environment['PATH'] = $SearchPath
        [void]$start.Environment.Remove('CODE_TRACER_JAR_DIR')
        if ($null -ne $JarDirectory) { $start.Environment['CODE_TRACER_JAR_DIR'] = [string]$JarDirectory }
        if ($Kind -eq 'cmd') {
            $start.FileName = $env:ComSpec
            # Quote for the Java/CRT argument parser; cmd metacharacters remain inside quotes.
            $quoted = foreach ($argument in $Arguments) {
                '"' + ($argument -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
            }
            $start.Arguments = '/d /v:off /s /c ""' + (Join-Path $LauncherDirectory $CommandName) + '" ' + ($quoted -join ' ') + '"'
        } else {
            $start.FileName = $pwshPath
            foreach ($argument in @('-NoLogo', '-NoProfile', '-File', (Join-Path $LauncherDirectory 'code-tracer.ps1')) + $Arguments) {
                $start.ArgumentList.Add($argument)
            }
        }
        $process = [Diagnostics.Process]::Start($start)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill($true)
            throw 'Launcher probe timed out'
        }
        $result = [pscustomobject]@{ Code = $process.ExitCode; Out = $stdout.Result; Err = $stderr.Result }
        $process.Dispose()
        return $result
    }

    function ConvertFrom-ProbeOutput {
        param([string] $Text)
        foreach ($line in ($Text -replace '\r\n', "`n").TrimEnd("`n").Split("`n")) {
            [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
        }
    }
}

AfterAll {
    Remove-Item -LiteralPath $testRoot -Recurse -Force
}

Describe 'PowerShell launcher' {
    It 'preserves argument boundaries, working directory, streams, and exit code' {
        $arguments = @('trace', '', 'a b', 'a"b', 'C:\path with spaces\', '*.java', 'a&b', 'café', '!literal!', 'last')
        $result = Invoke-LauncherProbe -Kind ps1 -Arguments $arguments
        $result.Code | Should -Be 23
        $result.Err | Should -BeExactly 'probe stderr'
        $decoded = @(ConvertFrom-ProbeOutput $result.Out)
        [IO.Path]::GetFullPath($decoded[0]) | Should -Be ([IO.Path]::GetFullPath($callerDir))
        $decoded[2..($decoded.Length - 1)] | Should -BeExactly $arguments
    }

    It 'resolves a symbolic link to the installed script' {
        $links = Join-Path $testRoot 'links'
        New-Item -ItemType Directory -Path $links -Force | Out-Null
        New-Item -ItemType SymbolicLink -Path "$links/code-tracer.ps1" -Target "$installDir/code-tracer.ps1" | Out-Null
        (Invoke-LauncherProbe -Kind ps1 -LauncherDirectory $links).Code | Should -Be 23
    }
}

Describe 'Shared Java selection and diagnostics' -ForEach @(@{ Kind = 'ps1' }, @{ Kind = 'cmd' }) {
    BeforeEach {
        if ($Kind -eq 'cmd' -and -not $IsWindows) { Set-ItResult -Skipped -Because 'Command Prompt requires Windows' }
    }
    It '<Kind> honors JAVA_HOME even when PATH has no Java' {
        (Invoke-LauncherProbe -Kind $Kind -SearchPath '').Code | Should -Be 23
    }
    It '<Kind> falls back to Java on PATH' {
        (Invoke-LauncherProbe -Kind $Kind -JavaHome '' -SearchPath "$selectedJdk/bin").Code | Should -Be 23
    }
    It '<Kind> rejects an invalid JAVA_HOME without falling back' {
        $result = Invoke-LauncherProbe -Kind $Kind -JavaHome "$testRoot/missing JDK"
        $result.Code | Should -Be 1
        $result.Out | Should -BeNullOrEmpty
        $result.Err | Should -Match 'JAVA_HOME'
    }
    It '<Kind> requires a compiler in an explicitly selected JDK' {
        $incomplete = Join-Path $testRoot "incomplete $Kind JDK"
        New-Item -ItemType Directory -Path "$incomplete/bin" -Force | Out-Null
        Copy-Item "$selectedJdk/bin/$javaName" "$incomplete/bin/$javaName"
        $result = Invoke-LauncherProbe -Kind $Kind -JavaHome $incomplete
        $result.Code | Should -Be 1
        $result.Out | Should -BeNullOrEmpty
        $result.Err | Should -Match 'javac'
    }
    It '<Kind> reports missing Java on stderr' {
        $result = Invoke-LauncherProbe -Kind $Kind -JavaHome '' -SearchPath ''
        $result.Code | Should -Be 1
        $result.Out | Should -BeNullOrEmpty
        $result.Err | Should -Match 'Java was not found'
    }
    It '<Kind> reports the missing adjacent JAR' {
        Move-Item "$installDir/code-tracer.jar" "$installDir/saved.jar"
        try {
            $result = Invoke-LauncherProbe -Kind $Kind
            $result.Code | Should -Be 1
            $result.Out | Should -BeNullOrEmpty
            $result.Err | Should -Match 'keep the launcher beside code-tracer.jar'
        } finally {
            Move-Item "$installDir/saved.jar" "$installDir/code-tracer.jar"
        }
    }
    It '<Kind> defaults to the adjacent JAR for an empty override' {
        (Invoke-LauncherProbe -Kind $Kind -JarDirectory '').Code | Should -Be 23
    }
    It '<Kind> uses literal absolute and caller-relative JAR directories' {
        $directory = Join-Path $callerDir 'jar files'
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        Move-Item "$installDir/code-tracer.jar" "$directory/code-tracer.jar"
        try {
            foreach ($value in @($directory, "$directory/", 'jar files', 'jar files/')) {
                $result = Invoke-LauncherProbe -Kind $Kind -JarDirectory $value -Arguments @('a b', '', 'last')
                $result.Code | Should -Be 23
                $decoded = @(ConvertFrom-ProbeOutput $result.Out)
                $decoded[0] | Should -Be $callerDir
                $decoded[2..4] | Should -BeExactly @('a b', '', 'last')
            }
            foreach ($literal in @('~', '$HOME')) {
                $literalPath = Join-Path $callerDir $literal
                [IO.Directory]::CreateDirectory($literalPath) | Out-Null
                [IO.File]::Copy("$directory/code-tracer.jar", "$literalPath/code-tracer.jar", $true)
                (Invoke-LauncherProbe -Kind $Kind -JarDirectory $literal).Code | Should -Be 23
            }
        } finally {
            Move-Item "$directory/code-tracer.jar" "$installDir/code-tracer.jar"
        }
    }
    It '<Kind> rejects invalid overrides even with a valid adjacent JAR' {
        $directory = Join-Path $callerDir 'not a jar'
        [IO.Directory]::CreateDirectory("$directory/code-tracer.jar") | Out-Null
        foreach ($value in @('missing directory', $directory)) {
            $result = Invoke-LauncherProbe -Kind $Kind -JarDirectory $value
            $result.Code | Should -Be 1
            $result.Out | Should -BeNullOrEmpty
            $result.Err | Should -Match 'CODE_TRACER_JAR_DIR'
            $result.Err | Should -Match ([regex]::Escape($value))
        }
    }
}

Describe 'Command Prompt launcher' {
    It 'returns control and the Java status to a calling batch script' -Skip:(-not $IsWindows) {
        @'
@echo off
call "%~dp0code-tracer.cmd" --version
set "saved_status=%errorlevel%"
>&2 echo parent resumed
exit /b %saved_status%
'@ | Set-Content "$installDir/parent.cmd" -Encoding ascii
        $result = Invoke-LauncherProbe -Kind cmd -CommandName parent.cmd
        $result.Code | Should -Be 23
        $result.Err | Should -Match 'probe stderrparent resumed'
    }

    It 'supports extensionless lookup beside the Bash copy' -Skip:(-not $IsWindows) {
        (Invoke-LauncherProbe -Kind cmd -CommandName code-tracer).Code | Should -Be 23
    }

    It 'preserves quoted arguments and runs without PowerShell on PATH' -Skip:(-not $IsWindows) {
        $arguments = @('trace', '', 'a b', 'C:\path with spaces\', '*.java', 'a&b', 'café', '!literal!', 'last')
        $result = Invoke-LauncherProbe -Kind cmd -Arguments $arguments -SearchPath ''
        $result.Code | Should -Be 23
        $result.Err | Should -BeExactly 'probe stderr'
        $decoded = @(ConvertFrom-ProbeOutput $result.Out)
        [IO.Path]::GetFullPath($decoded[0]) | Should -Be ([IO.Path]::GetFullPath($callerDir))
        $decoded[2..($decoded.Length - 1)] | Should -BeExactly $arguments
    }
}
