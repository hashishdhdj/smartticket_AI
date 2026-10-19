# Contract checks with a fake Docker command; no services stopped or application launched.
$ErrorActionPreference='Stop'
$ticketRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ticketStart=Join-Path $ticketRoot '06_项目源代码\ticket-service\start.ps1'
function docker {
    $global:LASTEXITCODE=0
    if($args[0] -eq 'version'){return 'fake-engine'}
    if($args[0] -eq 'inspect'){
        if($global:ticketStartupScenario -eq 'unhealthy'){return 'running unhealthy'}
        return 'running healthy'
    }
    if($args[0] -eq 'compose'){
        if($global:ticketStartupScenario -eq 'missing' -and $args[-1] -eq 'rabbitmq'){return}
        return 'fake-container-id'
    }
    throw 'Unexpected Docker command in CheckOnly'
}
try{
    Push-Location (Join-Path $ticketRoot '.tmp')
    try{
        foreach($global:ticketStartupScenario in @('missing','unhealthy')){
            $ticketRejected=$false
            try{& $ticketStart -CheckOnly}catch{
                if($_.Exception.Message -notlike '*Dependency check failed*'){throw}
                $ticketRejected=$true
            }
            if(-not $ticketRejected){throw "Unsafe readiness accepted: $global:ticketStartupScenario"}
        }
        $global:ticketStartupScenario='healthy'
        & $ticketStart -CheckOnly
    }finally{Pop-Location}
}finally{Remove-Item Function:\docker}
Write-Host 'PASS: missing/unhealthy dependencies rejected; healthy accepted from another working directory.'

