[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Origin = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Invoke-CapturedRequest {
    param([string]$Path, [hashtable]$Headers, [hashtable]$Body)
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -Method POST -Headers $Headers `
            -ContentType "application/json" -Body ($Body | ConvertTo-Json -Compress)
        return @{ Status = [int]$response.StatusCode; Body = $response.Content }
    }
    catch {
        $status = $null
        if ($null -ne $_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
        }
        return @{ Status = $status; Body = $_.ErrorDetails.Message }
    }
}

function Assert-Status {
    param([hashtable]$Result, [int[]]$Expected, [string]$Name)
    if ($Expected -notcontains $Result.Status) {
        throw "$Name 실패: expected=$($Expected -join ','), actual=$($Result.Status), body=$($Result.Body)"
    }
    Write-Host "$Name 성공: HTTP $($Result.Status)"
}

$validBody = @{
    provider = "google"
    code = "boundary-check-only"
    codeVerifier = (("a" * 43) -join "")
    redirectUri = "http://localhost:3000/auth/callback/google"
}

$missingOrigin = Invoke-CapturedRequest -Path "/auth/social" -Headers @{
    "X-Requested-With" = "MAC"
    Accept = "application/json"
} -Body $validBody
Assert-Status $missingOrigin @(403) "Origin 누락 거절"

$publicSocial = Invoke-CapturedRequest -Path "/auth/social" -Headers @{
    Origin = $Origin
    "X-Requested-With" = "MAC"
    Accept = "application/json"
} -Body $validBody
Assert-Status $publicSocial @(400, 502, 503) "public 소셜 라우팅"

$protectedReauth = Invoke-CapturedRequest -Path "/auth/social/reauth" -Headers @{
    Origin = $Origin
    "X-Requested-With" = "MAC"
    Accept = "application/json"
} -Body $validBody
Assert-Status $protectedReauth @(401) "JWT 없는 소셜 재인증 거절"

Write-Host "프론트 없는 소셜 HTTP 경계 테스트 성공" -ForegroundColor Green
