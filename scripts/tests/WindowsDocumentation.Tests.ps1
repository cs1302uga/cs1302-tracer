#Requires -Version 7.4

Describe 'Documented Windows JDK selection' -Skip:(-not $IsWindows) {
    BeforeAll {
        $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
        $guide = Get-Content -LiteralPath "$repoRoot/docs/guides/installation.md" -Raw
        $testRoot = Join-Path ([IO.Path]::GetTempPath()) "tracer documentation $([guid]::NewGuid())"
        $installDir = Join-Path $testRoot 'downloaded application'
        $conflictDir = Join-Path $testRoot 'conflicting tools'
        $invalidHome = Join-Path $testRoot 'missing JDK'
        $jdkLink = Join-Path $testRoot 'selected JDK with spaces'
        $pwshPath = (Get-Process -Id $PID).Path
        $selectedJdk = $env:JAVA_HOME
        if (-not $selectedJdk) {
            $javaPath = (Get-Command java.exe -CommandType Application | Select-Object -First 1).Source
            $selectedJdk = Split-Path (Split-Path $javaPath -Parent) -Parent
        }
        New-Item -ItemType Directory -Path $installDir, $conflictDir | Out-Null
        # A junction exercises quoted paths without copying or changing the CI JDK.
        New-Item -ItemType Junction -Path $jdkLink -Target $selectedJdk | Out-Null
        Copy-Item -LiteralPath "$repoRoot/target/code-tracer.jar" -Destination $installDir
        foreach ($tool in @('java', 'javac')) {
            "@echo off`r`necho WRONG_JDK_$tool`r`nexit /b 91`r`n" |
                Set-Content -LiteralPath "$conflictDir/$tool.cmd" -Encoding ascii
        }
        $conflictingPath = "$conflictDir;$env:PATH"
        $javaVersion = @(& "$selectedJdk/bin/java.exe" --version)[0]
        if ($LASTEXITCODE -ne 0) { throw 'The CI Java version probe failed' }
        $javacVersion = @(& "$selectedJdk/bin/javac.exe" --version)[0]
        if ($LASTEXITCODE -ne 0) { throw 'The CI compiler version probe failed' }

        function Get-DocumentedSnippet {
            param([string] $Marker)
            $pattern = '(?ms)^<!-- ' + [regex]::Escape($Marker) + ':start -->\r?\n' +
                '```[^\r\n]*\r?\n(?<code>.*?)\r?\n```\r?\n<!-- ' +
                [regex]::Escape($Marker) + ':end -->\r?$'
            $matches = [regex]::Matches($guide, $pattern)
            if ($matches.Count -ne 1) { throw "Expected exactly one documentation block: $Marker" }
            $code = $matches[0].Groups['code'].Value
            $placeholder = 'C:\path\to\your\jdk'
            $placeholderCount = [regex]::Matches($code, [regex]::Escape($placeholder)).Count
            if ($Marker.StartsWith('jdk-select-')) {
                if ($placeholderCount -ne 1) { throw "Expected one JDK placeholder: $Marker" }
                return $code.Replace($placeholder, $jdkLink)
            }
            if ($placeholderCount -ne 0) { throw "Unexpected JDK placeholder: $Marker" }
            return $code
        }

        function Invoke-DocumentationSession {
            param([string] $Kind, [string] $Code)
            $start = [Diagnostics.ProcessStartInfo]::new()
            $start.UseShellExecute = $false
            $start.RedirectStandardOutput = $true
            $start.RedirectStandardError = $true
            $start.WorkingDirectory = $installDir
            $start.Environment['JAVA_HOME'] = $invalidHome
            $start.Environment['PATH'] = $conflictingPath
            $start.Environment['NO_COLOR'] = '1'
            [void]$start.Environment.Remove('CODE_TRACER_JAR_DIR')
            $extension = if ($Kind -eq 'cmd') { 'cmd' } else { 'ps1' }
            $scriptPath = Join-Path $testRoot "documented session.$extension"
            Set-Content -LiteralPath $scriptPath -Value $Code -Encoding utf8NoBOM
            if ($Kind -eq 'cmd') {
                $start.FileName = $env:ComSpec
                $start.Arguments = '/d /v:off /s /c ""' + $scriptPath + '""'
            } else {
                $start.FileName = $pwshPath
                foreach ($argument in @('-NoLogo', '-NoProfile', '-File', $scriptPath)) {
                    $start.ArgumentList.Add($argument)
                }
            }
            $process = [Diagnostics.Process]::Start($start)
            try {
                $stdout = $process.StandardOutput.ReadToEndAsync()
                $stderr = $process.StandardError.ReadToEndAsync()
                if (-not $process.WaitForExit(60000)) {
                    $process.Kill($true)
                    throw 'The documented Windows commands timed out'
                }
                return [pscustomobject]@{ Code = $process.ExitCode; Out = $stdout.Result; Err = $stderr.Result }
            } finally {
                $process.Dispose()
            }
        }
    }

    AfterAll {
        # Remove the junction itself before recursively cleaning the fixture directory.
        if ($jdkLink -and (Test-Path -LiteralPath $jdkLink)) { Remove-Item -LiteralPath $jdkLink -Force }
        if ($testRoot -and (Test-Path -LiteralPath $testRoot)) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
    }

    It '<Kind> reports the stale home and conflicting commands before selection' -ForEach @(
        @{ Kind = 'powershell' }, @{ Kind = 'cmd' }
    ) {
        $inspect = Get-DocumentedSnippet "jdk-inspect-$Kind"
        $prefix = if ($Kind -eq 'cmd') { "@echo off`r`n" } else { '$ErrorActionPreference = "Stop"' + "`n" }
        $result = Invoke-DocumentationSession -Kind $Kind -Code ($prefix + $inspect)
        $result.Code | Should -Be 0 -Because $result.Err
        $result.Out | Should -Match ([regex]::Escape($invalidHome))
        $result.Out | Should -Match 'java\.cmd'
        $result.Out | Should -Match 'javac\.cmd'
    }

    It '<Kind> selects and verifies the real JDK and runs both documented tracer commands' -ForEach @(
        @{ Kind = 'powershell' }, @{ Kind = 'cmd' }
    ) {
        $select = Get-DocumentedSnippet "jdk-select-$Kind"
        $verify = Get-DocumentedSnippet "jdk-verify-$Kind"
        $tracer = Get-DocumentedSnippet "jdk-tracer-$Kind"
        if ($Kind -eq 'cmd') {
            # Keep every documented command verbatim, checking its status before the next one.
            $verify = ($verify -split '\r?\n' | ForEach-Object { $_; 'if errorlevel 1 exit /b 1' }) -join "`r`n"
            $tracer = ($tracer -split '\r?\n' | ForEach-Object { $_; 'if errorlevel 1 exit /b 1' }) -join "`r`n"
            $code = @"
@echo off
echo DOC_PATH_BEFORE=%PATH%
$select
echo DOC_HOME=%JAVA_HOME%
echo DOC_PATH=%PATH%
echo DOC_VERIFY_START
$verify
echo DOC_VERIFY_END
echo DOC_TRACER_START
$tracer
echo DOC_TRACER_END
"@
        } else {
            $code = @"
`$ErrorActionPreference = 'Stop'
`$PSNativeCommandUseErrorActionPreference = `$true
Write-Output "DOC_PATH_BEFORE=`$env:PATH"
$select
Write-Output "DOC_HOME=`$env:JAVA_HOME"
Write-Output "DOC_PATH=`$env:PATH"
Write-Output DOC_VERIFY_START
$verify
Write-Output DOC_VERIFY_END
Write-Output DOC_TRACER_START
$tracer
Write-Output DOC_TRACER_END
"@
        }
        $result = Invoke-DocumentationSession -Kind $Kind -Code $code
        $result.Code | Should -Be 0 -Because $result.Err
        $result.Out | Should -Not -Match 'WRONG_JDK'
        $lines = $result.Out -split '\r?\n'
        $lines | Should -Contain "DOC_HOME=$jdkLink"
        # PowerShell can prepend its own directory at startup, before the recipe runs.
        $beforeLines = @($lines | Where-Object { $_.StartsWith('DOC_PATH_BEFORE=') })
        $afterLines = @($lines | Where-Object { $_.StartsWith('DOC_PATH=') })
        $beforeLines.Count | Should -Be 1
        $afterLines.Count | Should -Be 1
        $beforePath = $beforeLines[0].Substring('DOC_PATH_BEFORE='.Length)
        $beforePath.Split([IO.Path]::PathSeparator) | Should -Contain $conflictDir
        $afterLines[0] | Should -BeExactly "DOC_PATH=$jdkLink\bin;$beforePath"
        $verified = [regex]::Match($result.Out, '(?s)DOC_VERIFY_START\r?\n(.*?)DOC_VERIFY_END').Groups[1].Value
        @($verified -split '\r?\n' | Where-Object { $_ -ceq $javaVersion }).Count | Should -Be 2
        @($verified -split '\r?\n' | Where-Object { $_ -ceq $javacVersion }).Count | Should -Be 2
        $verified | Should -Match ([regex]::Escape("$jdkLink\bin\java.exe"))
        $verified | Should -Match ([regex]::Escape("$jdkLink\bin\javac.exe"))
        $traced = [regex]::Match($result.Out, '(?s)DOC_TRACER_START\r?\n(.*?)DOC_TRACER_END').Groups[1].Value
        $versions = @([regex]::Matches($traced, '(?m)^code-tracer [^\r\n]+'))
        $versions.Count | Should -Be 2
        $versions[0].Value | Should -BeExactly $versions[1].Value
        $reports = @($traced.TrimEnd() -split '(?m)(?=^code-tracer )' | Where-Object { $_ })
        $reports.Count | Should -Be 2
        $reports[0].TrimEnd() | Should -BeExactly $reports[1].TrimEnd()
    }
}
