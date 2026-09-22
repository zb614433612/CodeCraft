# uia-dump.ps1 - UIAutomation element dump for SoM (Set-of-Mark) desktop automation (batch3)
#
# Enumerates interactive UI elements intersecting a physical-pixel region and writes a
# JSON array (UTF-8 without BOM) for the Java side (UiaElementDetector).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File uia-dump.ps1 -Region "x,y,w,h" -OutFile <path>
#
# JSON element fields: name / type / x / y / w / h  (ints, physical pixels)
#
# Design notes (verified by POC on Windows 1920x1080):
#   * SetProcessDpiAwareness(2) -> BoundingRectangle is in physical pixels (same space as Robot screenshots)
#   * Intersection filter drops hidden/tray windows parked at ~(-32000,-32000) whose IsOffscreen=false
#   * Taskbar items are ControlType "Pane" -> filter uses (interactive types UNION non-empty name)
#   * Duplicate rects deduped; result capped at $MaxElements (named elements first, stable order)
#   * ControlViewCondition enumeration is ~2x faster than TrueCondition with the same set

param(
    [string]$Region = "",
    [string]$OutFile = "uia-elements.json",
    [int]$MaxElements = 40,
    [int]$MinSize = 8
)

$ErrorActionPreference = 'Continue'

# ---- 1) Per-Monitor DPI awareness (best effort; failure ignored) ----
try {
    Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
public static class CodeCraftDpi {
    [DllImport("shcore.dll")] public static extern int SetProcessDpiAwareness(int value);
}
"@ -ErrorAction Stop
    [void][CodeCraftDpi]::SetProcessDpiAwareness(2)   # 2 = PROCESS_PER_MONITOR_DPI_AWARE
} catch { }

# ---- 2) Parse -Region "x,y,w,h" (physical pixels; x/y may be negative) ----
$hasRegion = $false
$regX = 0; $regY = 0; $regW = 0; $regH = 0
if ($Region -match '^\s*-?\d+\s*,\s*-?\d+\s*,\s*\d+\s*,\s*\d+\s*$') {
    $p = $Region -split ','
    $regX = [int]$p[0].Trim()
    $regY = [int]$p[1].Trim()
    $regW = [int]$p[2].Trim()
    $regH = [int]$p[3].Trim()
    if ($regW -gt 0 -and $regH -gt 0) { $hasRegion = $true }
}

# ---- 3) Strict-overlap intersection test (shared edges do not count) ----
function Test-RegionIntersect([double]$x1, [double]$y1, [double]$w1, [double]$h1,
                              [double]$x2, [double]$y2, [double]$w2, [double]$h2) {
    if ($w1 -le 0 -or $h1 -le 0 -or $w2 -le 0 -or $h2 -le 0) { return $false }
    return -not (($x1 + $w1) -le $x2 -or $x1 -ge ($x2 + $w2) -or ($y1 + $h1) -le $y2 -or $y1 -ge ($y2 + $h2))
}

# ---- 4) Load UIAutomation and enumerate (ControlViewCondition; POC-selected mode) ----
Add-Type -AssemblyName UIAutomationClient -ErrorAction Stop
Add-Type -AssemblyName UIAutomationTypes -ErrorAction Stop

$root = [System.Windows.Automation.AutomationElement]::RootElement
$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                     [System.Windows.Automation.Automation]::ControlViewCondition)

# Interactive control types (POC set). "Pane" intentionally absent: taskbar items are Pane-typed
# but have names, so they pass through the "non-empty name" branch of the filter.
$interactiveTypes = @('Button', 'Edit', 'ListItem', 'TabItem', 'MenuItem', 'CheckBox',
                      'ComboBox', 'Hyperlink', 'RadioButton', 'TreeItem', 'SplitButton')

# ---- 5) Filter / dedupe / split named vs unnamed (stable tree order per group) ----
# NOTE: use plain PowerShell arrays here - generic List[object] breaks ConvertTo-Json in PS 5.1.
$named = @()
$unnamed = @()
$seen = New-Object System.Collections.Generic.HashSet[string]

foreach ($el in $all) {
    try {
        $info = $el.Current
        $r = $info.BoundingRectangle
        if ($r.IsEmpty) { continue }
        if ($r.Width -lt $MinSize -or $r.Height -lt $MinSize) { continue }
        if ($info.IsOffscreen) { continue }
        if ($hasRegion -and -not (Test-RegionIntersect $r.X $r.Y $r.Width $r.Height $regX $regY $regW $regH)) { continue }

        $ct = $info.ControlType.ProgrammaticName -replace '^ControlType\.', ''
        $nm = $info.Name
        $hasName = $false
        $nameText = ''
        if ($nm -and $nm.Trim().Length -gt 0) {
            $hasName = $true
            $nameText = $nm.Trim()
        }
        if (-not $hasName -and -not ($interactiveTypes -contains $ct)) { continue }

        $key = '' + [int]$r.X + ',' + [int]$r.Y + ',' + [int]$r.Width + ',' + [int]$r.Height
        if ($seen.Contains($key)) { continue }
        [void]$seen.Add($key)

        $item = [PSCustomObject]@{
            name = $nameText
            type = $ct
            x = [int]$r.X
            y = [int]$r.Y
            w = [int]$r.Width
            h = [int]$r.Height
        }
        if ($hasName) { $named += $item } else { $unnamed += $item }
    } catch { }
}

# ---- 6) Cap: named elements first (stable), then unnamed (stable tree order) ----
$result = @()
foreach ($it in $named) {
    if ($result.Count -ge $MaxElements) { break }
    $result += $it
}
foreach ($it in $unnamed) {
    if ($result.Count -ge $MaxElements) { break }
    $result += $it
}

# ---- 7) Write JSON array (UTF-8 without BOM; file transport verified in POC) ----
$json = ConvertTo-Json -InputObject @($result) -Depth 3 -Compress
if ([string]::IsNullOrEmpty($json)) { $json = '[]' }
[System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))

# ASCII-safe console summary (stdout encoding is unreliable for non-ASCII)
Write-Output ("UIA_ELEMENTS=" + $result.Count)
Write-Output ("OUT=" + $OutFile)
