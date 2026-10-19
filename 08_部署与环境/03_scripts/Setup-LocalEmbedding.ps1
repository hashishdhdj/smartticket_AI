param([switch]$SkipPull)
$ErrorActionPreference='Stop'
$ticketProject=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ticketCompose=Join-Path $ticketProject '08_部署与环境\01_docker\compose-ollama.yml'
function Invoke-LocalDocker([string[]]$DockerArguments){
    # Windows PowerShell 5 represents ordinary native stderr progress as error records.
    $ticketOldPreference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue'; & docker @DockerArguments; $ticketDockerExit=$LASTEXITCODE}
    finally{$ErrorActionPreference=$ticketOldPreference}
    if($ticketDockerExit -ne 0){throw "Docker operation failed (exit $ticketDockerExit). Inspect the deployment log."}
}
Invoke-LocalDocker @('version','--format','{{.Server.Version}}') | Out-Null
$ticketDnsMode=python (Join-Path $PSScriptRoot 'prepare-ollama-dns.py')
if($LASTEXITCODE -ne 0){throw 'Download DNS preparation failed. No security checks were disabled.'}
$ticketComposeArgs=@('compose','-p','smartticket-embedding','-f',$ticketCompose)
if($ticketDnsMode -contains 'PUBLIC_DNS_OVERRIDE'){
    $ticketComposeArgs+=@('-f',(Join-Path $ticketProject '.tmp\ollama-public-dns.override.yml'))
    Write-Host 'Using freshly resolved public DNS for the official download host in the Ollama container only.'
}
Invoke-LocalDocker ($ticketComposeArgs+@('up','-d'))
$ticketDeadline=(Get-Date).AddSeconds(90)
do{
    try{$null=Invoke-RestMethod 'http://127.0.0.1:11434/api/version' -TimeoutSec 5; break}
    catch{if((Get-Date) -ge $ticketDeadline){throw 'Ollama API readiness deadline exceeded.'}; Start-Sleep -Seconds 2}
}while($true)
if(-not $SkipPull){
    Invoke-LocalDocker ($ticketComposeArgs+@('exec','-T','ollama','ollama','pull','bge-m3:latest'))
}
$ticketBody=@{model='bge-m3:latest'; input=@('合成工单：支付成功却没有订单'); encoding_format='float'} | ConvertTo-Json -Compress
$ticketWatch=[System.Diagnostics.Stopwatch]::StartNew()
$ticketResult=Invoke-RestMethod 'http://127.0.0.1:11434/v1/embeddings' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($ticketBody)) -TimeoutSec 180
if($ticketResult.data.Count -ne 1 -or $ticketResult.data[0].embedding.Count -ne 1024){throw 'Expected one 1024-dimensional vector.'}
$ticketModels=Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 10
$ticketModel=$ticketModels.models | Where-Object name -eq 'bge-m3:latest' | Select-Object -First 1
if(-not $ticketModel){throw 'Expected local BGE-M3 model missing.'}
$ticketEvidence=[ordered]@{status='PASS'; model=$ticketModel.name; digest=$ticketModel.digest; size_bytes=$ticketModel.size; dimensions=1024; first_request_ms=$ticketWatch.ElapsedMilliseconds; endpoint='http://127.0.0.1:11434/v1/embeddings'; execution_scope='LOCAL'; api_charge_rmb=0; date=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz')}
$ticketReport=Join-Path $ticketProject ('07_测试与质量\06_阶段测试报告\'+(Get-Date -Format 'yyyyMMdd_HHmmss')+'_本地BGE部署预检.json')
$ticketEvidence | ConvertTo-Json | Set-Content -LiteralPath $ticketReport -Encoding UTF8
Write-Host 'PASS: local BGE-M3 returned 1024 dimensions. No embedding API key or per-call fee.'
Write-Host "Evidence: $ticketReport"
