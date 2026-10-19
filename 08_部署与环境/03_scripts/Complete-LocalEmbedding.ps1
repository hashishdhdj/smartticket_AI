# Resumable local deployment. No paid chat calls. Progress survives the chat turn.
param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
$ticketRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ticketTemp=Join-Path $ticketRoot '.tmp'
New-Item -ItemType Directory -Path $ticketTemp -Force | Out-Null
$ticketStatusPath=Join-Path $ticketTemp 'phase4-local-deployment-state.json'
$ticketDeploymentLock=[System.IO.File]::Open((Join-Path $ticketTemp 'phase4-local-deployment.lock'),[System.IO.FileMode]::OpenOrCreate,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None)
$ticketState=[ordered]@{status='RUNNING'; stage='WAITING_IMAGE'; updated_at=''; model='bge-m3:latest'; paid_chat_calls=0; error_type=$null; process_id=$PID}
function Save-TicketDeploymentState {
    $ticketState.updated_at=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')
    $ticketState | ConvertTo-Json | Set-Content -LiteralPath $ticketStatusPath -Encoding UTF8
}
Save-TicketDeploymentState
Push-Location $ticketRoot
try{
    # Docker shares ongoing layer downloads; completed layers are reused on reruns.
    $ticketImagePreference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue'; docker image inspect ollama/ollama:latest *> $null; $ticketCachedImage=$LASTEXITCODE -eq 0}
    finally{$ErrorActionPreference=$ticketImagePreference}
    if(-not $ticketCachedImage){
        docker pull ollama/ollama:latest *> (Join-Path $ticketTemp 'phase4-ollama-image.log')
        if($LASTEXITCODE -ne 0){throw 'Official Ollama image download failed; inspect the image log and rerun.'}
    }
    $ticketState.stage='DOWNLOADING_MODEL'; Save-TicketDeploymentState
    & (Join-Path $PSScriptRoot 'Setup-LocalEmbedding.ps1') *> (Join-Path $ticketTemp 'phase4-ollama-install.log')
    $ticketState.stage='VERIFYING_ISOLATED_INDEX'; Save-TicketDeploymentState
    & (Join-Path $PSScriptRoot 'Verify-LocalEmbedding.ps1') -SkipBuild:$SkipBuild *> (Join-Path $ticketTemp 'phase4-ollama-verify.log')
    $ticketState.stage='VERIFYING_DEVELOPMENT_INDEX'; Save-TicketDeploymentState
    . (Join-Path $PSScriptRoot 'Build-Environment.ps1')
    python (Join-Path $PSScriptRoot 'verify-local-development.py') *> (Join-Path $ticketTemp 'phase4-ollama-development-verify.log')
    if($LASTEXITCODE -ne 0){throw 'Development Java API/index verification failed.'}
    $ticketState.status='PASS'; $ticketState.stage='COMPLETE'; Save-TicketDeploymentState
    Write-Host 'PASS: model, isolated index, and development Java retrieval verified. No paid chat calls.'
}catch{
    $ticketState.status='FAILED'; $ticketState.error_type=$_.Exception.GetType().Name
    Save-TicketDeploymentState
    throw
}finally{Pop-Location; $ticketDeploymentLock.Dispose()}
