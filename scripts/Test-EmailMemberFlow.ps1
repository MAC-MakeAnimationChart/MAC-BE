[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Origin = "http://localhost:3000",
    [string]$Email = "gimyongjun796@gmail.com",
    [string]$TermsVersion = "v1",
    [string]$PrivacyVersion = "v1",
    [ValidateRange(1, 20)]
    [int]$CodeAttempts = 5
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Convert-SecureValueToPlainText {
    param([Parameter(Mandatory)][Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

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

function Invoke-MacApi {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [string]$AccessToken,
        [switch]$PublicGet
    )

    $headers = @{ Accept = "application/json" }
    if (-not $PublicGet) {
        $headers.Origin = $Origin
        $headers["X-Requested-With"] = "MAC"
    }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    $parameters = @{
        Uri        = "$BaseUrl$Path"
        Method     = $Method
        Headers    = $headers
        WebSession = $session
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }

    try {
        return Invoke-RestMethod @parameters
    }
    catch {
        throw (Get-SafeHttpError $_)
    }
}

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$password = $null
$confirmation = $null
try {
    Write-Host "[0/8] 서버 상태 확인"
    $health = Invoke-MacApi -Method GET -Path "/health" -PublicGet
    Assert-Condition ($health.status -eq "UP") "서버 health가 UP이 아닙니다."

    $securePassword = Read-Host "테스트 회원 비밀번호" -AsSecureString
    $secureConfirmation = Read-Host "비밀번호 확인" -AsSecureString
    $password = Convert-SecureValueToPlainText $securePassword
    $confirmation = Convert-SecureValueToPlainText $secureConfirmation
    Assert-Condition ($password -ceq $confirmation) "비밀번호 확인값이 일치하지 않습니다."
    Assert-Condition ([Text.Encoding]::UTF8.GetByteCount($password) -le 72) "BCrypt 입력 한도인 UTF-8 72바이트를 초과했습니다."

    Write-Host "[1/8] 이메일 인증코드 발송: $Email"
    $sent = Invoke-MacApi -Method POST -Path "/auth/email/send-code" -Body @{
        email = $Email
        purpose = "signup"
    }
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$sent.challengeId)) "challengeId가 없습니다."

    Write-Host "[2/8] 이메일 인증코드 검증"
    $verified = $null
    for ($attempt = 1; $attempt -le $CodeAttempts; $attempt++) {
        $code = Read-Host "수신한 이메일 인증코드 ($attempt/$CodeAttempts)"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($code)) "인증코드가 비어 있습니다."
        try {
            $verified = Invoke-MacApi -Method POST -Path "/auth/email/verify" -Body @{
                challengeId = [string]$sent.challengeId
                code = $code
            }
            break
        }
        catch {
            if ($_.Exception.Message -match "HTTP 400.*INVALID_VERIFICATION") {
                if ($attempt -ge $CodeAttempts) {
                    throw "인증코드 확인 한도에 도달했습니다. 새 코드를 발송해 다시 시작해 주세요."
                }
                Write-Warning "인증코드가 올바르지 않거나 만료되었습니다. 다시 입력해 주세요."
                continue
            }
            throw
        }
    }
    Assert-Condition ($null -ne $verified) "이메일 인증 결과가 없습니다."
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$verified.verificationToken)) "verificationToken이 없습니다."
    $code = $null

    Write-Host "[3/8] 이메일 회원가입 및 자동 로그인"
    $signup = Invoke-MacApi -Method POST -Path "/auth/signup" -Body @{
        signupType = "EMAIL"
        email = $Email
        password = $password
        verificationToken = [string]$verified.verificationToken
        agreedTerms = @(
            @{ type = "TERMS"; version = $TermsVersion },
            @{ type = "PRIVACY"; version = $PrivacyVersion }
        )
    }
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$signup.accessToken)) "가입 Access JWT가 없습니다."
    Assert-Condition ($signup.member.hasPassword -eq $true) "EMAIL 회원의 hasPassword가 true가 아닙니다."
    $accessToken = [string]$signup.accessToken

    Write-Host "[4/8] 가입 직후 /me 자동 로그인 확인"
    $meAfterSignup = Invoke-MacApi -Method GET -Path "/me" -AccessToken $accessToken -PublicGet
    Assert-Condition ($meAfterSignup.email -eq $Email.Trim().ToLowerInvariant()) "가입 회원 이메일이 일치하지 않습니다."

    Write-Host "[5/8] 로그아웃"
    $null = Invoke-MacApi -Method POST -Path "/auth/logout"
    $accessToken = $null

    Write-Host "[6/8] 이메일·비밀번호 로그인"
    $login = Invoke-MacApi -Method POST -Path "/auth/login" -Body @{
        email = $Email
        password = $password
    }
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$login.accessToken)) "로그인 Access JWT가 없습니다."
    $accessToken = [string]$login.accessToken

    Write-Host "[7/8] 로그인 후 /me 확인"
    $meAfterLogin = Invoke-MacApi -Method GET -Path "/me" -AccessToken $accessToken -PublicGet
    Assert-Condition ($meAfterLogin.email -eq $Email.Trim().ToLowerInvariant()) "로그인 회원 이메일이 일치하지 않습니다."
    Assert-Condition (@($meAfterLogin.providers).Count -eq 0) "EMAIL 전용 회원에 소셜 provider가 존재합니다."

    Write-Host "[8/8] Refresh 회전"
    $refreshed = Invoke-MacApi -Method POST -Path "/auth/refresh"
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$refreshed.accessToken)) "회전된 Access JWT가 없습니다."

    Write-Host "이메일 회원 통합 흐름 성공: memberId=$($meAfterLogin.id), email=$($meAfterLogin.email)" -ForegroundColor Green
}
finally {
    $password = $null
    $confirmation = $null
    $accessToken = $null
    $verified = $null
    $signup = $null
    $login = $null
    $refreshed = $null
}
