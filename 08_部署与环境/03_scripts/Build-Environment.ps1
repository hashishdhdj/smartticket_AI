# Dot-source to discover process-local build tools.
$ErrorActionPreference = 'Stop'
$ticketRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ticketService = Join-Path $ticketRoot '06_项目源代码\ticket-service'
$ticketTemp = Join-Path $ticketRoot '.tmp'
New-Item -ItemType Directory -Path $ticketTemp -Force | Out-Null
$ticketJdk = $env:JAVA_HOME
if (-not $ticketJdk -or -not (Test-Path -LiteralPath ($ticketJdk + '\bin\javac.exe') -ErrorAction SilentlyContinue)) {
    $ticketJdkBase = Join-Path $ticketRoot '09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime'
    $ticketJdkDir = Get-ChildItem -LiteralPath $ticketJdkBase -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-17*' } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ticketJdkDir) { throw 'Set JAVA_HOME to JDK 17 or install project-local JDK 17.' }
    $ticketJdk = $ticketJdkDir.FullName
}
$ticketJava = Join-Path $ticketJdk 'bin\java.exe'
$ticketJavaPreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    $ticketJavaInfo = & $ticketJava -version 2>&1 | Out-String
    $ticketJavaExit=$LASTEXITCODE
} finally { $ErrorActionPreference=$ticketJavaPreference }
if ($ticketJavaExit -ne 0 -or $ticketJavaInfo -notmatch 'version "17[.]') { throw 'JDK 17 required. Check JAVA_HOME.' }
$env:JAVA_HOME = $ticketJdk
$ticketMavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($ticketMavenCommand) { $ticketMaven = $ticketMavenCommand.Source }
else {
    $ticketMavenBase = Join-Path $ticketRoot '09_项目依赖与资源\02_下载链接与校验\安装包\Maven'
    $ticketMavenDir = Get-ChildItem -LiteralPath $ticketMavenBase -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'apache-maven-*' } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ticketMavenDir) { throw 'Install Maven on PATH or project-local Maven.' }
    $ticketMaven = Join-Path $ticketMavenDir.FullName 'bin\mvn.cmd'
}
if (-not (Test-Path -LiteralPath $ticketMaven)) { throw 'Maven executable missing.' }
$ticketMavenInfo = & $ticketMaven -v 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Maven environment check failed.' }
Write-Host ($ticketMavenInfo | Select-Object -First 1)
Write-Host 'JDK 17 verified. No system-level settings changed.'


