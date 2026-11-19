[CmdletBinding()]
param(
    [string[]]$Files,
    [string]$BaseRef = 'origin/main',
    [string[]]$LocalMarkers
)

$ErrorActionPreference = 'Stop'
$root = (& git rev-parse --show-toplevel).Trim()
Set-Location -LiteralPath $root

$baseCommit = (& git rev-parse --verify "$BaseRef^{commit}" 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($baseCommit | Out-String).Trim())) {
    throw "Base ref not found: $BaseRef"
}
$baseCommit = ($baseCommit | Select-Object -First 1).Trim()
$pendingCommits = @(& git rev-list "$BaseRef..HEAD")

$fromIndex = -not $PSBoundParameters.ContainsKey('Files')
if (-not $fromIndex) {
    $candidates = @($Files)
} else {
    $candidates = @(
        & git -c core.quotePath=false diff --cached --name-only --diff-filter=ACMRTUXB
    )
}

$candidates = @(
    $candidates |
        ForEach-Object { $_.Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
)

if ($candidates.Count -eq 0 -and $pendingCommits.Count -eq 0) {
    throw 'No staged files or pending commits to verify.'
}

$deniedPath = [regex]'(?i)(^|/)(keystore\.properties|local\.properties|secrets\.properties|\.env(\..*)?|[^/]+\.(jks|keystore|p12|pem|key|apk|aab|log)|[^/]*明细[^/]*\.csv|handoff\.md)$'
$deniedTree = [regex]'(?i)(^|/)(\.gradle|build|\.diag|\.tile-tests|docs/change-reviews|_backups|\.codex-backups)(/|$)'
$backslash = [char]92
$slash = '/'
$users = 'Users'
$localMarkerExpressions = @(
    [regex]::Escape('C:' + $backslash + $users + $backslash),
    [regex]::Escape('C:' + $slash + $users + $slash),
    [regex]::Escape($slash + $users + $slash),
    ('DESKTOP-' + '[A-Z0-9-]+')
)
$configuredMarkers = @()
if ($LocalMarkers) {
    $configuredMarkers += $LocalMarkers
}
if (-not [string]::IsNullOrWhiteSpace($env:BIKEMAP_LOCAL_MARKERS)) {
    $configuredMarkers += [regex]::Split(
        $env:BIKEMAP_LOCAL_MARKERS,
        '[\r\n,;]+'
    )
}
foreach ($marker in $configuredMarkers) {
    $trimmed = $marker.Trim()
    if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
        $localMarkerExpressions += [regex]::Escape($trimmed)
    }
}
$localMarkerPattern = '(?i)(' + ($localMarkerExpressions -join '|') + ')'
$secretMarkerPattern = '(?i)(BEGIN ' + '(RSA |EC |OPENSSH )?PRIVATE KEY|' +
        'ghp_' + '[A-Za-z0-9]{20,}|' +
        'sk-' + '[A-Za-z0-9]{20,}|' +
        'Bearer ' + '[A-Za-z0-9._-]{12,})'
$localMarker = [regex]$localMarkerPattern
$secretMarker = [regex]$secretMarkerPattern
$nineDigits = [regex]'(?<!\d)\d{9}(?!\d)'
$textExtension = [regex]'(?i)\.(java|kt|kts|xml|json|csv|md|txt|properties|ya?ml|ps1|sh|bat)$'
$publicNumberPath = [regex]'(?i)^(app/src/main/assets/vehicle_number_catalog\.json|docs/developer-data/vehicle-number/[^/]*编号范围[^/]*\.(csv|md))$'

$catalogPath = Join-Path $root 'app/src/main/assets/vehicle_number_catalog.json'
$catalogNumbers = [System.Collections.Generic.HashSet[string]]::new()
if (Test-Path -LiteralPath $catalogPath) {
    $catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($property in $catalog.numbers.PSObject.Properties) {
        [void]$catalogNumbers.Add($property.Name)
    }
}

$errors = [System.Collections.Generic.List[string]]::new()
foreach ($candidate in $candidates) {
    $relative = $candidate.Replace('\', '/').TrimStart('./')
    if ($deniedPath.IsMatch($relative) -or $deniedTree.IsMatch($relative)) {
        $errors.Add("Denied path: $relative")
        continue
    }

    $fullPath = Join-Path $root $candidate
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        $errors.Add("Missing file: $relative")
        continue
    }
    $resolvedPath = (Resolve-Path -LiteralPath $fullPath).Path
    $rootPrefix = (Resolve-Path -LiteralPath $root).Path.TrimEnd('\', '/') +
            [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedPath.StartsWith(
            $rootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
    )) {
        $errors.Add("Path escapes repository: $relative")
        continue
    }

    if (-not $textExtension.IsMatch($relative)) {
        continue
    }
    if ($fromIndex) {
        $unstaged = @(& git -c core.quotePath=false diff --name-only -- $candidate)
        if ($unstaged.Count -gt 0) {
            $errors.Add("Staged file also has unstaged changes: $relative")
            continue
        }
    }
    $item = Get-Item -LiteralPath $fullPath
    if ($item.Length -gt 2MB) {
        continue
    }
    $content = Get-Content -LiteralPath $fullPath -Raw -Encoding UTF8
    if ($localMarker.IsMatch($content)) {
        $errors.Add("Local machine marker: $relative")
    }
    if ($secretMarker.IsMatch($content)) {
        $errors.Add("Credential marker: $relative")
    }
    if (-not $publicNumberPath.IsMatch($relative)) {
        foreach ($match in $nineDigits.Matches($content)) {
            if ($catalogNumbers.Contains($match.Value)) {
                $errors.Add("Catalog vehicle number '$($match.Value)': $relative")
            }
        }
    }
}

if ($pendingCommits.Count -gt 0) {
    $historyFile = ''
    $historyCommit = ''
    $historyPatch = @(
        & git -c core.quotePath=false log --no-ext-diff --unified=0 --format='commit %H' "$BaseRef..HEAD" --
    )
    foreach ($line in $historyPatch) {
        if ($line -match '^commit ([0-9a-f]{40})$') {
            $historyCommit = $matches[1]
            $historyFile = ''
            continue
        }
        if ($line -match '^diff --git a/(.+) b/(.+)$') {
            $historyFile = $matches[2].Replace('\', '/')
            if ($deniedPath.IsMatch($historyFile) -or $deniedTree.IsMatch($historyFile)) {
                $errors.Add("History denied path: $($historyFile) @ $($historyCommit)")
            }
            continue
        }
        if (-not $line.StartsWith('+') -or $line.StartsWith('+++')) {
            continue
        }
        $addedText = $line.Substring(1)
        $historyLabel = "$($historyFile) @ $($historyCommit)"
        if ($localMarker.IsMatch($addedText)) {
            $errors.Add("History local machine marker: $historyLabel")
        }
        if ($secretMarker.IsMatch($addedText)) {
            $errors.Add("History credential marker: $historyLabel")
        }
        if (-not $publicNumberPath.IsMatch($historyFile)) {
            foreach ($match in $nineDigits.Matches($addedText)) {
                if ($catalogNumbers.Contains($match.Value)) {
                    $errors.Add(
                        "History catalog vehicle number '$($match.Value)': $historyLabel"
                    )
                }
            }
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { [Console]::Error.WriteLine($_) }
    throw 'Upload boundary verification failed.'
}

Write-Output (
    "Upload boundary verification passed for $($candidates.Count) current file(s) " +
    "and $($pendingCommits.Count) pending commit(s):"
)
$candidates | ForEach-Object { Write-Output "  $_" }
