#Requires -Version 7.4
Describe 'Maven CLI launcher from native Windows shells' {
    BeforeAll {
        $repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
        $repository = Join-Path $repoRoot 'target/launcher-repository'
        [xml]$pom = Get-Content -Raw "$repoRoot/target/maven-plugin/pom.xml"
        $goal = "io.github.cs1302uga:cs1302-tracer-maven-plugin:$($pom.project.version):run"
        $caller = Join-Path $TestDrive 'caller without pom'
        New-Item -ItemType Directory "$caller/source files" -Force | Out-Null
        'public class Main { public static void main(String[] args) { System.out.println(42); } }' |
            Set-Content -LiteralPath "$caller/source files/Main.java" -Encoding utf8
    }
    It 'preserves a source path with spaces through PowerShell and mvnw.cmd' {
        Push-Location $caller
        try {
            Test-Path pom.xml | Should -BeFalse
            $output = & "$repoRoot/mvnw.cmd" -q "-Dmaven.repo.local=$repository" $goal "-Dtracer.args=trace --input 'source files/Main.java' --format modern"
            $LASTEXITCODE | Should -Be 0
            ($output -join "`n" | ConvertFrom-Json).steps.Count | Should -BeGreaterThan 0
        } finally { Pop-Location }
    }
    It 'preserves a source path with spaces through Command Prompt' {
        $batch = Join-Path $caller 'run.cmd'
        @"
@echo off
call "$repoRoot/mvnw.cmd" -q "-Dmaven.repo.local=$repository" $goal "-Dtracer.args=trace --input 'source files/Main.java' --format modern" > trace.json
exit /b %errorlevel%
"@ | Set-Content -LiteralPath $batch -Encoding ascii
        Push-Location $caller
        try {
            & $env:ComSpec /d /c $batch
            $LASTEXITCODE | Should -Be 0
            (Get-Content -Raw trace.json | ConvertFrom-Json).steps.Count | Should -BeGreaterThan 0
        } finally { Pop-Location }
    }
}
