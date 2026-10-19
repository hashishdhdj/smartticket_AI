# Zero paid-model calls: package, load actual Java chunks, rebuild isolated vectors, calibrate.
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Build-Environment.ps1')
function Invoke-TicketPython([string]$Script){
    python (Join-Path $PSScriptRoot $Script)
    if($LASTEXITCODE -ne 0){throw "Verification failed: $Script"}
}
Push-Location $ticketRoot
try{
    # Keep cloud secrets untouched; change only the selected provider line.
    $ticketProviderFile=Join-Path $ticketTemp 'phase4-embedding-provider.properties'
    $ticketProviderText=if(Test-Path -LiteralPath $ticketProviderFile){[System.IO.File]::ReadAllText($ticketProviderFile)}else{''}
    if($ticketProviderText -match '(?m)^provider='){
        $ticketProviderText=[regex]::Replace($ticketProviderText,'(?m)^provider=[^\r\n]*','provider=ollama')
    }else{$ticketProviderText="provider=ollama`n"+$ticketProviderText}
    [System.IO.File]::WriteAllText($ticketProviderFile,$ticketProviderText,(New-Object System.Text.UTF8Encoding($false)))
    & (Join-Path $PSScriptRoot 'Setup-LocalEmbedding.ps1') -SkipPull
    Invoke-TicketPython 'prepare-phase4.py'
    if(-not $SkipBuild){
        Push-Location $ticketService
        try{
            & $ticketMaven -o -Punit "-DargLine=-Djava.io.tmpdir=$ticketTemp -Djdk.net.unixdomain.tmpdir=$ticketTemp" package
            if($LASTEXITCODE -ne 0){throw 'Unit tests/package failed.'}
        }finally{Pop-Location}
    }
    Invoke-TicketPython 'export-java-chunks.py'
    Invoke-TicketPython 'stage4-calibrate-rag.py'
    Write-Host 'PASS: isolated application rebuilt and checked the actual local-model vectors. Chat was disabled.'
}finally{Pop-Location}
