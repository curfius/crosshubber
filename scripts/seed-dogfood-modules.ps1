# Seeds the dogfood modules (solutions + staffing) with demo data for the AI-plan
# E2E scenarios (AI_MODULES_PLAN H4 prep). Authenticates with a portal-signed
# agent-call token minted from the compose SESSION_SECRET (never printed).
#
# Usage:  powershell -File scripts\seed-dogfood-modules.ps1
# Re-runnable: re-seeding creates additional clients/projects (no upserts in v1).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# == 1. Mint the agent-call token (secret read silently) ==
$secretLine = Get-Content (Join-Path $root 'config-management\tenants-config\dev\secrets.env') |
  Where-Object { $_ -match '^SESSION_SECRET=' } | Select-Object -First 1
$secret = if ($secretLine) { $secretLine -replace '^SESSION_SECRET=', '' } else { '' }
if (-not $secret) { $secret = 'change-me-session-secret-32-chars-min' }

$claims = @{
  iss   = 'portal'
  sub   = 'seed-script'
  name  = 'Seed Script'
  roles = @('solutions-user', 'staffing-user')
  exp   = [DateTimeOffset]::UtcNow.AddMinutes(10).ToUnixTimeSeconds()
} | ConvertTo-Json -Compress
$payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($claims)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
$sig = [Convert]::ToBase64String($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload))).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$token = "$payload.$sig"
$headers = @{ 'X-Portal-Agent' = $token; 'Content-Type' = 'application/json' }

# == 2. Seed solutions ==
$solutions = 'http://localhost:28090/api'
$clients = Invoke-RestMethod -Method Get -Uri "$solutions/clients" -Headers $headers
$client = $clients.clients | Where-Object { $_.name -eq 'Acme Manufacturing' } | Select-Object -First 1
if (-not $client) {
  $client = Invoke-RestMethod -Method Post -Uri "$solutions/clients" -Headers $headers `
    -Body (@{ name = 'Acme Manufacturing'; industry = 'Industrial'; accountOwner = 'Seed Script' } | ConvertTo-Json)
  Write-Host '[solutions] client created: Acme Manufacturing'
} else {
  Write-Host '[solutions] client exists: Acme Manufacturing'
}
$clientId = $client.id

$projects = Invoke-RestMethod -Method Get -Uri "$solutions/projects" -Headers $headers
if ($projects.projects.Count -eq 0) {
  $p1 = Invoke-RestMethod -Method Post -Uri "$solutions/projects" -Headers $headers `
    -Body (@{ clientId = $clientId; name = 'ERP Rollout'; owner = 'Ana' } | ConvertTo-Json)
  $t = @{ stageData = @{ goDecision = 'go'; pursuitOwner = 'Ana'; estimate = '180k EUR'; decisionDate = '2026-09-15' } }
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'qualified'; note = 'seed' } + $t | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'proposal'; note = 'seed'; stageData = @{ scope = 'finance + warehouse'; team = '6 people'; pricing = 'fixed 180k'; timeline = '24 weeks' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'sent'; note = 'seed'; stageData = @{ sentDate = '2026-09-20'; version = '1'; channel = 'email' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'negotiation'; note = 'seed'; stageData = @{ version = '2'; clientFeedback = 'price pressure'; nextStep = 'revised proposal' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'won'; note = 'seed'; stageData = @{ contractRef = 'AC-2026-14'; budget = 180000; startDate = '2026-10-01' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'implementation'; note = 'seed'; stageData = @{ kickoffDate = '2026-10-05'; milestones = @('m1-finance', 'm2-warehouse', 'm3-crm'); budgetBurn = '55%'; health = 'at-risk' } } | ConvertTo-Json -Depth 5) | Out-Null
  foreach ($doc in @(
    @{ documentRef = 'fake:proposal-scope'; title = 'Proposal - ERP Rollout (scope, team, pricing, timeline)' },
    @{ documentRef = 'fake:architecture-notes'; title = 'Architecture notes - integrations and risks' },
    @{ documentRef = 'fake:steering-minutes'; title = 'Steering committee minutes - week 12' })) {
    Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p1.id)/documents" -Headers $headers `
      -Body (@{ documentRef = $doc.documentRef; title = $doc.title; ragEnabled = $true } | ConvertTo-Json) | Out-Null
  }
  Write-Host '[solutions] ERP Rollout seeded: implementation / at-risk, 3 RAG docs'

  $p2 = Invoke-RestMethod -Method Post -Uri "$solutions/projects" -Headers $headers `
    -Body (@{ clientId = $clientId; name = 'CRM Integration'; owner = 'Bruno' } | ConvertTo-Json)
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p2.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'qualified'; note = 'seed'; stageData = @{ goDecision = 'go'; pursuitOwner = 'Bruno'; estimate = '60k EUR'; decisionDate = '2026-09-25' } } | ConvertTo-Json -Depth 5) | Out-Null
  Write-Host '[solutions] CRM Integration seeded: qualified'

  $p3 = Invoke-RestMethod -Method Post -Uri "$solutions/projects" -Headers $headers `
    -Body (@{ clientId = $clientId; name = 'WMS Upgrade'; owner = 'Carla' } | ConvertTo-Json)
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p3.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'qualified'; note = 'seed'; stageData = @{ goDecision = 'go'; pursuitOwner = 'Carla'; estimate = '40k EUR'; decisionDate = '2026-09-10' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p3.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'proposal'; note = 'seed'; stageData = @{ scope = 'wms'; team = '3'; pricing = 'fixed 40k'; timeline = '12 weeks' } } | ConvertTo-Json -Depth 5) | Out-Null
  Invoke-RestMethod -Method Post -Uri "$solutions/projects/$($p3.id)/transitions" -Headers $headers `
    -Body (@{ toStage = 'lost'; note = 'seed'; stageData = @{ reason = 'price'; lessons = 'underestimated migration effort' } } | ConvertTo-Json -Depth 5) | Out-Null
  Write-Host '[solutions] WMS Upgrade seeded: lost'
} else {
  Write-Host "[solutions] $($projects.projects.Count) project(s) already present - skipping"
}

# == 3. Seed staffing ==
$staffing = 'http://localhost:28091/api'
$rfps = Invoke-RestMethod -Method Get -Uri "$staffing/rfps" -Headers $headers
if ($rfps.rfps.Count -eq 0) {
  $rfp = Invoke-RestMethod -Method Post -Uri "$staffing/rfps" -Headers $headers `
    -Body (@{
      client = 'Acme Manufacturing'; title = 'Java squad for ERP rollout'; kind = 'rfp'
      deadline = '2026-10-15T00:00:00Z'
      requirements = @{ skills = @('java', 'spring', 'angular', 'docker'); seniority = 'senior'; languages = @('en'); availability = 'immediate' }
    } | ConvertTo-Json -Depth 5)
  Invoke-RestMethod -Method Post -Uri "$staffing/rfps/$($rfp.id)/transitions" -Headers $headers `
    -Body (@{ toStatus = 'analyzing' } | ConvertTo-Json) | Out-Null
  foreach ($cv in @(
    @{ documentRef = 'fake:cv-ana'; title = 'Ana Pereira' },
    @{ documentRef = 'fake:cv-bruno'; title = 'Bruno Costa' },
    @{ documentRef = 'fake:cv-carla'; title = 'Carla Mendes' })) {
    Invoke-RestMethod -Method Post -Uri "$staffing/candidates/scan" -Headers $headers `
      -Body (@{ documentRef = $cv.documentRef; title = $cv.title } | ConvertTo-Json) | Out-Null
  }
  Write-Host '[staffing] RFP + 3 CV profiles seeded'
} else {
  Write-Host "[staffing] $($rfps.rfps.Count) RFP(s) already present - skipping"
}

# == 4. Verify the agent tools end to end (direct dispatch, portal contract) ==
Write-Host "`n=== tool verification (direct /agent/tools dispatch) ==="
$proj = Invoke-RestMethod -Method Post -Uri 'http://localhost:28090/agent/tools/list_projects' -Headers $headers `
  -Body '{"tool":"list_projects","arguments":{}}'
$atRisk = $proj.projects | Where-Object { $_.health -eq 'at-risk' } | Select-Object -First 1
Write-Host ("list_projects -> {0} project(s); at-risk: {1} [{2}]" -f $proj.projects.Count, $atRisk.name, $atRisk.stage)

$history = Invoke-RestMethod -Method Post -Uri 'http://localhost:28090/agent/tools/get_stage_history' -Headers $headers `
  -Body ('{"tool":"get_stage_history","arguments":{"projectId":"' + $atRisk.id + '"}}')
Write-Host ("get_stage_history -> {0} transition event(s)" -f $history.events.Count)

$docs = Invoke-RestMethod -Method Post -Uri 'http://localhost:28090/agent/tools/search_project_docs' -Headers $headers `
  -Body ('{"tool":"search_project_docs","arguments":{"projectId":"' + $atRisk.id + '","query":"milestone budget risk"}}')
Write-Host ("search_project_docs -> top snippet from: {0}" -f $docs.snippets[0].title)

$match = Invoke-RestMethod -Method Post -Uri 'http://localhost:28091/agent/tools/match_candidates' -Headers $headers `
  -Body ('{"tool":"match_candidates","arguments":{"rfpId":"' + $rfps.rfps[0].id + '","topN":3}}')
Write-Host ("match_candidates -> 1. {0} (score {1}); 2. {2} (score {3})" -f `
  $match.matches[0].name, $match.matches[0].score, $match.matches[1].name, $match.matches[1].score)

$run = Invoke-RestMethod -Method Post -Uri 'http://localhost:28091/agent/tools/create_match_run' -Headers $headers `
  -Body ('{"tool":"create_match_run","arguments":{"rfpId":"' + $rfps.rfps[0].id + '","topN":3}}')
Write-Host ("create_match_run -> run {0} with {1} ranked result(s)" -f $run.runId, $run.results.Count)

Write-Host "`nDone. Next: install 'staffing' via the Module Registry (http://staffing:8091), then chat:"
Write-Host '  - "Which projects are at risk?"     (S2: solutions_list_projects via the portal agent)'
Write-Host '  - "Summarize project ERP Rollout docs" (S4-lite: search_project_docs + citations)'
Write-Host '  - "Match candidates for the Acme RFP"  (S5: staffing tools)'
