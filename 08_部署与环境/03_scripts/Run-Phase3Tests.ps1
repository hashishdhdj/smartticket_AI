param([string]$Tests='', [switch]$Offline, [switch]$AllowVendor, [ValidateSet('all','unit','integration')][string]$Profile='all')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Build-Environment.ps1')
if($env:AI_SMOKE -eq 'true' -and (-not $AllowVendor -or -not $Tests)){throw 'AI_SMOKE=true requires -AllowVendor and explicit -Tests. Default runs must not call vendors.'}
$ticketArgs=@("-DargLine=-Djava.io.tmpdir=$ticketTemp -Djdk.net.unixdomain.tmpdir=$ticketTemp",'-Dsmartticket.vector.index-on-startup=false')
if($Offline){$ticketArgs+='-o'}
if($Profile -ne 'all'){$ticketArgs+=('-P'+$Profile)}
if($Profile -eq 'integration'){
    $ticketIntegration=Join-Path $ticketTemp 'phase4-integration.properties'
    if(-not(Test-Path -LiteralPath $ticketIntegration)){throw 'Run prepare-phase4.py before isolated integration tests.'}
    $ticketArgs+=('-Dspring.config.additional-location=file:'+($ticketIntegration -replace '\\','/'))
}
if($Tests){$ticketArgs+=('-Dtest='+$Tests)}
Push-Location $ticketService
try{& $ticketMaven @ticketArgs test; if($LASTEXITCODE -ne 0){throw 'Tests failed; inspect target/surefire-reports.'}}
finally{Pop-Location}
