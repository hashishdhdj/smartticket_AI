param([switch]$Offline)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Build-Environment.ps1')
Push-Location $ticketRoot
try{
    python (Join-Path $PSScriptRoot 'prepare-delivery-source.py')
    if($LASTEXITCODE -ne 0){throw 'Clean source snapshot failed.'}
    $ticketDeliveryInfo=Get-Content -LiteralPath (Join-Path $ticketTemp 'phase4-delivery-build.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $ticketArgs=@('-Punit',"-DargLine=-Djava.io.tmpdir=$ticketTemp -Djdk.net.unixdomain.tmpdir=$ticketTemp",'package')
    if($Offline){$ticketArgs=@('-o')+$ticketArgs}
    Push-Location $ticketDeliveryInfo.directory
    try{
        $ticketPreviousPreference=$ErrorActionPreference
        try{$ErrorActionPreference='Continue'; & $ticketMaven @ticketArgs *> (Join-Path $ticketTemp 'phase4-clean-delivery-build.log'); $ticketDeliveryExit=$LASTEXITCODE}
        finally{$ErrorActionPreference=$ticketPreviousPreference}
        if($ticketDeliveryExit -ne 0){throw 'Clean delivery build failed; inspect .tmp/phase4-clean-delivery-build.log.'}
    }finally{Pop-Location}
    python (Join-Path $PSScriptRoot 'verify-delivery.py')
    if($LASTEXITCODE -ne 0){throw 'Clean delivery artifact/startup verification failed.'}
}finally{Pop-Location}
