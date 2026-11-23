<#
.SYNOPSIS
    End-to-end demo against the running stack: top up a wallet, transfer, refund, reconcile.

.DESCRIPTION
    Creates a fresh merchant (with its own API key), two EUR wallets, and then walks through the
    money flows. Every money-moving call sends an Idempotency-Key; one is deliberately retried.
    Works in Windows PowerShell 5.1 and PowerShell 7.

.EXAMPLE
    Copy-Item .env.example .env        # once
    docker compose up -d --build
    .\scripts\demo.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8480"
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'
if (-not (Test-Path $envFile)) {
    throw "No .env file. Run: Copy-Item .env.example .env"
}
# The admin token is read from .env and never printed.
$adminLine = Get-Content $envFile | Where-Object { $_ -match '^ADMIN_API_TOKEN=' } | Select-Object -First 1
if (-not $adminLine) { throw "ADMIN_API_TOKEN is missing from .env" }
$adminToken = $adminLine.Substring('ADMIN_API_TOKEN='.Length).Trim()

function Invoke-Api {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [string]$Token,
        $Body = $null,
        [string]$IdempotencyKey = $null
    )
    $headers = @{ Authorization = "Bearer $Token" }
    if ($IdempotencyKey) { $headers['Idempotency-Key'] = $IdempotencyKey }
    $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        # Always send JSON: PowerShell 5.1 would otherwise send a form content type
        $request.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
        $request.ContentType = 'application/json'
    }
    try {
        $response = Invoke-WebRequest @request
        $replayed = $response.Headers['Idempotent-Replayed']
        if ($replayed -is [array]) { $replayed = $replayed[0] }
        return [pscustomobject]@{
            Status   = [int]$response.StatusCode
            Body     = $(if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null })
            Replayed = $replayed
        }
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $text = $_.ErrorDetails.Message
        if (-not $text -and ($_.Exception.Response | Get-Member -Name GetResponseStream)) {
            # Windows PowerShell 5.1 leaves ErrorDetails empty for application/problem+json
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $text = $reader.ReadToEnd()
        }
        return [pscustomobject]@{
            Status   = [int]$_.Exception.Response.StatusCode
            Body     = $(if ($text) { $text | ConvertFrom-Json } else { $null })
            Replayed = $null
        }
    }
}

function Assert-Status($response, [int]$expected, [string]$what) {
    if ($response.Status -ne $expected) {
        throw "$what returned HTTP $($response.Status), expected $expected. $($response.Body | ConvertTo-Json -Depth 10 -Compress)"
    }
}

function Wait-Status([string]$path, [string]$token, [string]$expected) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        $current = (Invoke-Api -Method Get -Path $path -Token $token).Body
        if ($current.status -eq $expected) { return $current }
        Start-Sleep -Milliseconds 250
    }
    throw "$path did not reach $expected within 30 s (still $($current.status))"
}

function New-Key([string]$purpose) { "$purpose-$([guid]::NewGuid())" }

function Show-Balances([string]$label) {
    $a = (Invoke-Api -Method Get -Path "/api/wallets/$alice" -Token $apiKey).Body.balance.formatted
    $b = (Invoke-Api -Method Get -Path "/api/wallets/$bob" -Token $apiKey).Body.balance.formatted
    Write-Host ("    {0,-34} alice {1,8} EUR   bob {2,8} EUR" -f $label, $a, $b)
}

function Step([string]$text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }

# ---- 0. wait for the API ---------------------------------------------------------------------
Step "Waiting for the ledger service at $BaseUrl"
$deadline = (Get-Date).AddSeconds(90)
while ($true) {
    try {
        $probe = Invoke-Api -Method Get -Path "/admin/reconciliations?limit=1" -Token $adminToken
        if ($probe.Status -eq 200) { break }
    } catch {
        if ((Get-Date) -gt $deadline) { throw "The ledger service is not reachable at $BaseUrl. Is 'docker compose up' running?" }
    }
    Start-Sleep -Seconds 2
}
Write-Host "    up"

# ---- 1. onboarding ---------------------------------------------------------------------------
Step "Create a merchant (admin token) and two EUR wallets (merchant API key)"
$merchant = Invoke-Api -Method Post -Path "/admin/merchants" -Token $adminToken -Body @{ name = "Demo Coffee $(Get-Date -Format 'HHmmss')" }
Assert-Status $merchant 201 "create merchant"
$apiKey = $merchant.Body.apiKey
Write-Host "    merchant $($merchant.Body.merchantId), API key $($merchant.Body.keyPrefix)... (shown once, stored only as a SHA-256 hash)"
$alice = (Invoke-Api -Method Post -Path "/api/wallets" -Token $apiKey -Body @{ customerRef = "alice"; currency = "EUR" }).Body.id
$bob = (Invoke-Api -Method Post -Path "/api/wallets" -Token $apiKey -Body @{ customerRef = "bob"; currency = "EUR" }).Body.id
Show-Balances "new wallets"

# ---- 2. top-up (saga + webhook) --------------------------------------------------------------
Step "Top up alice with 100.00 EUR by card (202 Accepted; the provider confirms by signed webhook)"
$topUpKey = New-Key "topup"
$topUpBody = @{ amountMinor = 10000; currency = "EUR"; paymentMethod = "pm_card_ok" }
$topUp = Invoke-Api -Method Post -Path "/api/wallets/$alice/top-ups" -Token $apiKey -Body $topUpBody -IdempotencyKey $topUpKey
Assert-Status $topUp 202 "top-up"
$payment = $topUp.Body.id
Write-Host "    payment $payment is $($topUp.Body.status)"
$done = Wait-Status "/api/payments/$payment" $apiKey "SUCCEEDED"
Write-Host "    ...provider charge $($done.providerChargeId): SUCCEEDED"
Show-Balances "after the top-up"

Step "Retry the same top-up with the same Idempotency-Key (a client that timed out)"
$retry = Invoke-Api -Method Post -Path "/api/wallets/$alice/top-ups" -Token $apiKey -Body $topUpBody -IdempotencyKey $topUpKey
Write-Host "    HTTP $($retry.Status), Idempotent-Replayed: $($retry.Replayed), same payment: $($retry.Body.id -eq $payment)"
Show-Balances "still charged once"

Step "Top up alice with 20.00 EUR via pm_card_chaos (webhook delivered twice, stale event arrives last)"
$chaos = Invoke-Api -Method Post -Path "/api/wallets/$alice/top-ups" -Token $apiKey -IdempotencyKey (New-Key "topup") `
    -Body @{ amountMinor = 2000; currency = "EUR"; paymentMethod = "pm_card_chaos" }
Assert-Status $chaos 202 "chaos top-up"
Wait-Status "/api/payments/$($chaos.Body.id)" $apiKey "SUCCEEDED" | Out-Null
Start-Sleep -Seconds 2   # let the duplicate and the stale event arrive too
Show-Balances "credited exactly once"
try {
    $lines = docker compose -f (Join-Path $repoRoot 'docker-compose.yml') logs --since 15s ledger-service 2>$null |
        Select-String -Pattern 'Duplicate webhook|IGNORED'
    foreach ($line in $lines) { Write-Host "    log: $(($line.Line -split '\|', 2)[-1].Trim())" -ForegroundColor DarkGray }
} catch {
    Write-Verbose "Could not read container logs: $_"
}

# ---- 3. transfer -----------------------------------------------------------------------------
Step "Transfer 30.00 EUR from alice to bob (one ACID transaction, both wallets locked in a fixed order)"
$transfer = Invoke-Api -Method Post -Path "/api/transfers" -Token $apiKey -IdempotencyKey (New-Key "transfer") `
    -Body @{ fromWalletId = $alice; toWalletId = $bob; amountMinor = 3000; currency = "EUR"; description = "Coffee beans" }
Assert-Status $transfer 201 "transfer"
Show-Balances "after the transfer"

Step "Try to transfer 500.00 EUR from bob (more than he has)"
$overdraft = Invoke-Api -Method Post -Path "/api/transfers" -Token $apiKey -IdempotencyKey (New-Key "transfer") `
    -Body @{ fromWalletId = $bob; toWalletId = $alice; amountMinor = 50000; currency = "EUR" }
Write-Host "    HTTP $($overdraft.Status) $($overdraft.Body.code): $($overdraft.Body.detail)"

# ---- 4. refund (saga with compensation) ------------------------------------------------------
Step "Refund 25.00 EUR of the first top-up (reserved at once, settled when the provider confirms)"
$refund = Invoke-Api -Method Post -Path "/api/payments/$payment/refunds" -Token $apiKey -IdempotencyKey (New-Key "refund") `
    -Body @{ amountMinor = 2500; reason = "Customer changed their mind" }
Assert-Status $refund 202 "refund"
Show-Balances "refund reserved"
$refundDone = Wait-Status "/api/refunds/$($refund.Body.id)" $apiKey "SUCCEEDED"
Write-Host "    ...provider refund $($refundDone.providerRefundId): SUCCEEDED"

Step "Alice's statement (newest first)"
$statement = (Invoke-Api -Method Get -Path "/api/wallets/$alice/entries" -Token $apiKey).Body
foreach ($line in $statement) {
    Write-Host ("    {0,-16} {1,9} EUR   {2}" -f $line.type, $line.amount.formatted, $line.description)
}

# ---- 5. reconciliation -----------------------------------------------------------------------
Step "Run reconciliation for today (admin)"
$report = (Invoke-Api -Method Post -Path "/admin/reconciliations" -Token $adminToken).Body
$mine = @($report.positions | Where-Object { $_.merchantId -eq $merchant.Body.merchantId })
$myFindings = @($report.findings | Where-Object { $_.merchantId -eq $merchant.Body.merchantId })
foreach ($check in $report.checks) {
    $mark = if ($check.findings -eq 0) { 'ok ' } else { 'NO ' }
    Write-Host ("    [{0}] {1,-30} {2}" -f $mark, $check.name, $check.description)
}
foreach ($p in $mine) {
    Write-Host ("    balance sheet {0}: cash at provider {1} = wallets {2} + refunds in transit {3} -> balanced: {4}" -f `
        $p.currency, $p.providerClearingMinor, $p.walletsMinor, $p.refundsInTransitMinor, $p.balanced)
}
Write-Host "    overall status: $($report.status) ($($report.findings.Count) findings in total, $($myFindings.Count) for this demo merchant)"
if ($report.status -ne 'BALANCED') {
    Write-Host "    (Findings for other merchants usually mean provider-sim was restarted: it keeps its records in memory.)" -ForegroundColor Yellow
}
if ($myFindings.Count -gt 0) { throw "Reconciliation found problems with the demo merchant's books" }
Write-Host ""
Write-Host "Demo complete." -ForegroundColor Green
