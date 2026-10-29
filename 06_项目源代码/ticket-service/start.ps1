param([ValidateSet('offline-demo','local-rag','real-model')][string]$Mode='offline-demo', [ValidateRange(1,65535)][int]$Port=8080, [ValidateRange(1,600)][int]$HealthTimeoutSeconds=120, [switch]$Offline, [switch]$CheckOnly, [ValidateSet('configured','siliconflow','ollama')][string]$EmbeddingProvider='configured', [switch]$ReindexKnowledge, [switch]$AgentReception)
$ErrorActionPreference='Stop'
if($Mode -eq 'local-rag' -and $EmbeddingProvider -ne 'ollama'){throw 'local-rag mode requires -EmbeddingProvider ollama.'}
if($ReindexKnowledge -and $Mode -eq 'offline-demo'){throw 'Indexing requires local-rag or real-model mode.'}
$ticketProject=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $ticketProject '08_部署与环境\03_scripts\Build-Environment.ps1')
$ticketCompose=Join-Path $ticketRoot '08_部署与环境\01_docker'
foreach($ticketConfig in @((Join-Path $ticketCompose '.env'),(Join-Path $ticketService 'src\main\resources\application-local.yml'),(Join-Path $ticketService 'phase3-local.properties'))){
    if(-not(Test-Path -LiteralPath $ticketConfig)){throw "Missing configuration: $ticketConfig. See deployment env examples and prepare-phase3.py."}
}
docker version --format '{{.Server.Version}}' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Docker engine unavailable. Start Docker Desktop.'}
Push-Location $ticketCompose
try{
    if(-not $CheckOnly){docker compose up -d; if($LASTEXITCODE -ne 0){throw 'Compose startup failed.'}}
    $ticketDeadline=(Get-Date).AddSeconds($HealthTimeoutSeconds)
    do{
        $ticketHealthy=$true
        foreach($ticketDependency in @('mysql','redis','pgvector','rabbitmq')){
            $ticketContainerIds=@(docker compose ps -a -q $ticketDependency)
            if($LASTEXITCODE -ne 0 -or $ticketContainerIds.Count -ne 1){$ticketHealthy=$false; continue}
            $ticketState=docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing-healthcheck{{end}}' $ticketContainerIds[0]
            if($LASTEXITCODE -ne 0 -or $ticketState -ne 'running healthy'){$ticketHealthy=$false}
        }
        if($ticketHealthy){break}
        if($CheckOnly){throw 'Dependency check failed: all four services must be running and healthy.'}
        Start-Sleep -Seconds 2
    }while((Get-Date) -lt $ticketDeadline)
    if(-not $ticketHealthy){throw 'Dependency health deadline exceeded. Inspect compose ps and logs.'}
}finally{Pop-Location}
Write-Host 'All four dependencies healthy; local configuration files present.'
if($CheckOnly){Write-Host 'CheckOnly: no application or model started.'; return}
if($EmbeddingProvider -eq 'ollama' -and $Mode -ne 'offline-demo'){
    try{$ticketLocalModels=Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 5}
    catch{throw 'Local Ollama is unavailable. Run Setup-LocalEmbedding.ps1 first.'}
    if(-not($ticketLocalModels.models | Where-Object name -eq 'bge-m3:latest')){throw 'Local bge-m3:latest missing. Run Setup-LocalEmbedding.ps1 first.'}
}
$ticketArgs=@("-Dspring-boot.run.jvmArguments=-Djava.io.tmpdir=$ticketTemp -Djdk.net.unixdomain.tmpdir=$ticketTemp")
if($Offline){$ticketArgs+='-o'}
$ticketApplicationArgs="--server.port=$Port --smartticket.vector.index-on-startup=$($ReindexKnowledge.IsPresent.ToString().ToLowerInvariant())"
$ticketProfiles=@('local')
if($EmbeddingProvider -in @('siliconflow','ollama')){$ticketProfiles+=$EmbeddingProvider}
if($AgentReception){$ticketProfiles+='reception';Write-Host 'Agent-first reception enabled for new tickets. Apply sql/09_agent_reception.sql first; answers await customer confirmation.'}
if($ticketProfiles.Count -gt 1){$ticketApplicationArgs+=" --spring.profiles.active=$($ticketProfiles -join ',')"}
if($Mode -eq 'offline-demo'){$ticketApplicationArgs+=' --smartticket.ai.enabled=false --spring.ai.openai.api-key=not-configured --smartticket.vector.embedding.api-key=not-configured'}
elseif($Mode -eq 'local-rag'){
    $ticketApplicationArgs+=' --smartticket.ai.enabled=false --spring.ai.openai.api-key=not-configured --smartticket.phase3.async-enabled=false'
    Write-Host 'Local RAG: local embeddings enabled; chat disabled. Retrieval can be checked without vendor calls.'
}
else{Write-Host 'Real-model mode: external calls may incur costs. Background async remains enabled.'}
$ticketArgs+="-Dspring-boot.run.arguments=$ticketApplicationArgs"
Push-Location $ticketService
try{& $ticketMaven @ticketArgs spring-boot:run; if($LASTEXITCODE -ne 0){throw 'Application failed. Check configuration, migrations and Maven output.'}}
finally{Pop-Location}
