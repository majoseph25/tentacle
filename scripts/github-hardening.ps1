# Copyright 2026 Mark Joseph
# SPDX-License-Identifier: Apache-2.0
#
# Turns on the GitHub security settings recommended in docs/SECURITY_ASSESSMENT.md.
# Safe to run more than once. Needs GitHub CLI (gh), signed in with admin rights on the repository.
#
# Some features are only available on public repositories (or on paid plans), so run this again
# after making the repository public; it reports what was enabled and what GitHub refused.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/github-hardening.ps1 [-Repo owner/name]

param([string]$Repo = 'majoseph25/tentacle')

$ErrorActionPreference = 'Continue'
$tmp = [System.IO.Path]::GetTempFileName()

function Invoke-Setting([string]$label, [string[]]$apiArgs) {
    $out = & gh api @apiArgs 2>&1
    if ($LASTEXITCODE -eq 0) {
        "{0,-36} on" -f $label
    } else {
        $msg = [string]((($out | Out-String) -split "`n") | Where-Object { $_ -match 'HTTP \d{3}' } | Select-Object -First 1)
        "{0,-36} not available ({1})" -f $label, ($msg -replace '^.*gh:\s*', '' -replace '\s+At .*$', '').Trim()
    }
}

"Repository: $Repo (" + (gh repo view $Repo --json visibility --jq .visibility) + ")"
""

# 1. Security features
Invoke-Setting 'Dependabot alerts' @('-X', 'PUT', "repos/$Repo/vulnerability-alerts")
Invoke-Setting 'Dependabot security updates' @('-X', 'PUT', "repos/$Repo/automated-security-fixes")
Invoke-Setting 'Private vulnerability reporting' @('-X', 'PUT', "repos/$Repo/private-vulnerability-reporting")
'{"security_and_analysis":{"secret_scanning":{"status":"enabled"},"secret_scanning_push_protection":{"status":"enabled"}}}' |
    Set-Content $tmp -Encoding ascii
Invoke-Setting 'Secret scanning + push protection' @('-X', 'PATCH', "repos/$Repo", '--input', $tmp)

# 2. Branch protection for main (a ruleset): no deleting or force-pushing main; changes arrive by pull
#    request and must pass the Android CI "build" check. Repository admins (the owner) can bypass, so
#    the owner is never locked out of their own repository.
$existing = gh api "repos/$Repo/rulesets" --jq '.[] | select(.name == \"Protect main\") | .id' 2>$null
@'
{
  "name": "Protect main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [ { "actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always" } ],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [ { "context": "build" } ] } }
  ]
}
'@ | Set-Content $tmp -Encoding ascii
if ("$existing" -match '^\d+$') {
    Invoke-Setting 'Branch protection (update ruleset)' @('-X', 'PUT', "repos/$Repo/rulesets/$existing", '--input', $tmp)
} else {
    Invoke-Setting 'Branch protection (create ruleset)' @('-X', 'POST', "repos/$Repo/rulesets", '--input', $tmp)
}

Remove-Item $tmp -ErrorAction SilentlyContinue
""
"Current security settings:"
gh api "repos/$Repo" --jq '.security_and_analysis'
