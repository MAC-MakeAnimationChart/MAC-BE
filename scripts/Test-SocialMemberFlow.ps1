[CmdletBinding()]
param(
    [ValidateSet("google", "kakao", "naver")]
    [string]$Provider = "google",
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Origin = "http://localhost:3000",
    [string]$RedirectUri,
    [string]$TermsVersion = "v1",
    [string]$PrivacyVersion = "v1",
    [switch]$TestReauth
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($RedirectUri)) {
    $RedirectUri = "http://localhost:3000/auth/callback/$Provider"
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Get-SafeHttpError {
    param([Parameter(Mandatory)]$ErrorRecord)
    $status = $null
    $response = $ErrorRecord.Exception.Response
    if ($null -ne $response) {
        try { $status = [int]$response.StatusCode } catch { }
    }
    $detail = $null
    if ($null -ne $ErrorRecord.ErrorDetails) { $detail = $ErrorRecord.ErrorDetails.Message }
    if ([string]::IsNullOrWhiteSpace($detail) -and $null -ne $response) {
        try {
            if ($null -ne $response.PSObject.Methods["GetResponseStream"]) {
                $reader = New-Object IO.StreamReader($response.GetResponseStream())
                try { $detail = $reader.ReadToEnd() } finally { $reader.Dispose() }
            }
        }
        catch { }
    }
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = "응답 본문 없음" }
    return "HTTP $status - $detail"
}

function Invoke-SocialRequest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][hashtable]$Body,
        [string]$AccessToken
    )
    $headers = @{
        Origin = $Origin
        "X-Requested-With" = "MAC"
        Accept = "application/json"
    }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }
    try {
        return Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -Method POST -Headers $headers `
            -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 8 -Compress) `
            -WebSession $session
    }
    catch {
        throw (Get-SafeHttpError $_)
    }
}

function Read-ProviderProof {
    param([string]$Label)
    $code = Read-Host "$Label provider authorization code"
    if ([string]::IsNullOrWhiteSpace($code)) { throw "provider code가 비어 있습니다." }
    $verifier = $null
    $state = $null
    if ($Provider -eq "naver") {
        $state = Read-Host "$Label FE에서 검증한 Naver state"
        if ([string]::IsNullOrWhiteSpace($state)) { throw "Naver state가 비어 있습니다." }
    }
    else {
        $verifier = Read-Host "$Label 원래 PKCE code verifier"
        if ($verifier -notmatch '^[A-Za-z0-9._~-]{43,128}$') {
            throw "PKCE verifier는 허용 문자로 구성된 43~128자여야 합니다."
        }
    }
    return @{ Code = $code; Verifier = $verifier; State = $state }
}

function New-SocialBody {
    param([hashtable]$Proof)
    return @{
        provider = $Provider
        code = $Proof.Code
        codeVerifier = $Proof.Verifier
        redirectUri = $RedirectUri
        state = $Proof.State
    }
}

function Invoke-Me {
    param([string]$AccessToken)
    try {
        return Invoke-RestMethod -Uri "$BaseUrl/me" -Method GET -Headers @{
            Authorization = "Bearer $AccessToken"
            Accept = "application/json"
        } -WebSession $session
    }
    catch {
        throw (Get-SafeHttpError $_)
    }
}

$accessToken = $null
$proof = $null
$reauthProof = $null
try {
    $proof = Read-ProviderProof "로그인"
    Write-Host "[1/4] $Provider code 교환"
    $exchangeResponse = Invoke-SocialRequest -Path "/auth/social" -Body (New-SocialBody $proof)
    $exchange = $exchangeResponse.Content | ConvertFrom-Json

    if ([int]$exchangeResponse.StatusCode -eq 200) {
        Write-Host "기존 소셜 identity 로그인"
        $accessToken = [string]$exchange.accessToken
    }
    elseif ([int]$exchangeResponse.StatusCode -eq 202) {
        if ($exchange.signupRequired -ne $true) { throw "202 응답의 signupRequired가 true가 아닙니다." }
        Write-Host "[2/4] 신규 소셜 회원 약관 동의 및 가입"
        $signupResponse = Invoke-SocialRequest -Path "/auth/signup" -Body @{
            signupType = "SOCIAL"
            agreedTerms = @(
                @{ type = "TERMS"; version = $TermsVersion },
                @{ type = "PRIVACY"; version = $PrivacyVersion }
            )
        }
        if ([int]$signupResponse.StatusCode -ne 201) { throw "SOCIAL 가입 응답이 201이 아닙니다." }
        $signup = $signupResponse.Content | ConvertFrom-Json
        $accessToken = [string]$signup.accessToken
    }
    else {
        throw "예상하지 못한 social 응답: HTTP $($exchangeResponse.StatusCode)"
    }

    if ([string]::IsNullOrWhiteSpace($accessToken)) { throw "Access JWT가 없습니다." }

    Write-Host "[3/4] 소셜 로그인 /me 확인"
    $me = Invoke-Me $accessToken
    if ($me.hasPassword -ne $false) { throw "소셜 회원의 hasPassword가 false가 아닙니다." }
    if (@($me.providers).Count -lt 1) { throw "소셜 provider 연결이 없습니다." }

    if ($TestReauth) {
        $reauthProof = Read-ProviderProof "재인증"
        Write-Host "탈퇴용 소셜 재인증 쿠키 발급 확인"
        $reauthResponse = Invoke-SocialRequest -Path "/auth/social/reauth" `
            -Body (New-SocialBody $reauthProof) -AccessToken $accessToken
        if ([int]$reauthResponse.StatusCode -ne 204) { throw "소셜 재인증 응답이 204가 아닙니다." }
    }

    Write-Host "[4/4] 소셜 로그아웃"
    $logout = Invoke-SocialRequest -Path "/auth/logout" -Body @{}
    if ([int]$logout.StatusCode -ne 204) { throw "로그아웃 응답이 204가 아닙니다." }

    Write-Host "소셜 회원 통합 흐름 성공: memberId=$($me.id), providers=$(@($me.providers) -join ',')" -ForegroundColor Green
}
finally {
    $accessToken = $null
    $proof = $null
    $reauthProof = $null
    $exchange = $null
    $signup = $null
}
