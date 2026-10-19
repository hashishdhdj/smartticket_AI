param()

$ErrorActionPreference = 'Stop'

# Locate the existing project-local JDK and the classpath resolved by Maven tests.
$ticketProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ticketServiceRoot = Join-Path $ticketProjectRoot '06_项目源代码\ticket-service'
$ticketJdkBin = Join-Path $ticketProjectRoot '09_项目依赖与资源\02_下载链接与校验\安装包\JDK\jdk-17-runtime\jdk-17.0.20.1+1\bin'
$ticketReportPath = Join-Path $ticketServiceRoot 'target\surefire-reports\TEST-com.smartticket.service.TicketCacheTest.xml'
$ticketOutputPath = Join-Path $ticketServiceRoot 'target\draft-review'
$ticketMessageSource = Join-Path $PSScriptRoot 'TicketAnalysisMessage.java'
# After the draft is accepted, verify the actual source used by the service.
if (-not (Test-Path -LiteralPath $ticketMessageSource)) {
    $ticketMessageSource = Join-Path $ticketServiceRoot 'src\main\java\com\smartticket\mq\TicketAnalysisMessage.java'
}
if (-not (Test-Path -LiteralPath $ticketMessageSource)) {
    throw 'TicketAnalysisMessage.java was not found in the draft or service source directory.'
}

if (-not (Test-Path -LiteralPath (Join-Path $ticketJdkBin 'javac.exe'))) {
    throw 'Project-local JDK compiler was not found.'
}
if (-not (Test-Path -LiteralPath $ticketReportPath)) {
    throw 'Run TicketCacheTest first: its Maven report supplies the resolved dependency classpath.'
}
[xml]$ticketReport = Get-Content -Raw -Encoding UTF8 -LiteralPath $ticketReportPath
$ticketClasspath = ($ticketReport.testsuite.properties.property |
    Where-Object { $_.name -eq 'java.class.path' }).value
if (-not $ticketClasspath) {
    throw 'The Maven test report does not contain java.class.path.'
}

New-Item -ItemType Directory -Path $ticketOutputPath -Force | Out-Null
Write-Host '[1/2] Compiling the message draft and JSON check...'
& (Join-Path $ticketJdkBin 'javac.exe') -encoding UTF-8 -cp $ticketClasspath -d $ticketOutputPath `
    $ticketMessageSource `
    (Join-Path $PSScriptRoot 'TicketAnalysisMessageJsonCheck.java')
if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed (exit code $LASTEXITCODE)."
}

Write-Host '[2/2] Running the JSON round-trip check...'
& (Join-Path $ticketJdkBin 'java.exe') -cp "$ticketOutputPath;$ticketClasspath" `
    com.smartticket.mq.TicketAnalysisMessageJsonCheck
if ($LASTEXITCODE -ne 0) {
    throw "JSON verification failed (exit code $LASTEXITCODE)."
}
