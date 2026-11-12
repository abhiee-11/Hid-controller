# Windows-side HID evidence probe for the phone-as-controller test.
#
# Samples three input surfaces at once and prints only state CHANGES:
#   - keyboard  : GetAsyncKeyState across the standard virtual-key set
#   - mouse     : GetCursorPos deltas + button transitions
#   - gamepad   : joyGetPosEx (buttons, axes, POV) for every enumerated stick
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\pc-hid-probe.ps1 -Seconds 30
#
# Keep hands off this PC's keyboard/mouse while sampling, so any recorded event
# must have come from the phone.
param(
    [int]$Seconds = 20,
    [int]$IntervalMs = 10,
    [string]$OutFile = ""
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class HidProbeNative
{
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X; public int Y; }

    [DllImport("user32.dll")]
    public static extern bool GetCursorPos(out POINT p);

    [StructLayout(LayoutKind.Sequential)]
    public struct JOYINFOEX
    {
        public uint dwSize;
        public uint dwFlags;
        public uint dwXpos;
        public uint dwYpos;
        public uint dwZpos;
        public uint dwRpos;
        public uint dwUpos;
        public uint dwVpos;
        public uint dwButtons;
        public uint dwButtonNumber;
        public uint dwPOV;
        public uint dwReserved1;
        public uint dwReserved2;
    }

    [DllImport("winmm.dll")]
    public static extern int joyGetNumDevs();

    [DllImport("winmm.dll")]
    public static extern int joyGetPosEx(int uJoyID, ref JOYINFOEX pji);
}
"@

$JOY_RETURNALL = 0xFF

# Virtual keys worth reporting. Anything outside this map is ignored so the
# output stays readable.
$vkNames = @{
    0x08 = 'Backspace'; 0x09 = 'Tab'; 0x0D = 'Enter'; 0x1B = 'Escape'; 0x20 = 'Space'
    0x25 = 'Left'; 0x26 = 'Up'; 0x27 = 'Right'; 0x28 = 'Down'
    0x10 = 'Shift'; 0x11 = 'Ctrl'; 0x12 = 'Alt'
    0x30 = '0'; 0x31 = '1'; 0x32 = '2'; 0x33 = '3'; 0x34 = '4'
    0x35 = '5'; 0x36 = '6'; 0x37 = '7'; 0x38 = '8'; 0x39 = '9'
}
foreach ($c in [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZ') { $vkNames[[int][char]$c] = [string]$c }

$events = New-Object System.Collections.Generic.List[string]
function Add-Event([string]$text) {
    $events.Add(("{0:HH:mm:ss.fff}  {1}" -f (Get-Date), $text))
    Write-Host ("{0:HH:mm:ss.fff}  {1}" -f (Get-Date), $text)
}

function Read-Joysticks {
    $found = @()
    $count = [HidProbeNative]::joyGetNumDevs()
    for ($id = 0; $id -lt $count; $id++) {
        $info = New-Object HidProbeNative+JOYINFOEX
        $info.dwSize = [System.Runtime.InteropServices.Marshal]::SizeOf($info)
        $info.dwFlags = $JOY_RETURNALL
        $rc = [HidProbeNative]::joyGetPosEx($id, [ref]$info)
        if ($rc -eq 0) {
            $found += [pscustomobject]@{
                Id      = $id
                Buttons = $info.dwButtons
                X       = $info.dwXpos
                Y       = $info.dwYpos
                Z       = $info.dwZpos
                Rz      = $info.dwRpos
                Pov     = $info.dwPOV
            }
        }
    }
    return $found
}

function Pad-Signature($pad) {
    "b=$($pad.Buttons) x=$($pad.X) y=$($pad.Y) z=$($pad.Z) rz=$($pad.Rz) pov=$($pad.Pov)"
}

$prevKeys = @{}
$prevMouseX = -1; $prevMouseY = -1
$prevButtons = 0
$prevPads = @{}

# Baseline: what Windows sees before we start. Seeding $prevPads from it means a pad that is
# already visible is not reported as "appeared" on the first sample.
$baselinePads = Read-Joysticks
Add-Event ("gamepads visible at start: {0}" -f $(if ($baselinePads.Count) { ($baselinePads | ForEach-Object { "id=$($_.Id)" }) -join ',' } else { 'none' }))
foreach ($pad in $baselinePads) { $prevPads["pad$($pad.Id)"] = Pad-Signature $pad }
$cursor = New-Object HidProbeNative+POINT
[void][HidProbeNative]::GetCursorPos([ref]$cursor)
$prevMouseX = $cursor.X; $prevMouseY = $cursor.Y
Add-Event ("cursor start: X={0} Y={1}" -f $cursor.X, $cursor.Y)

$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    # Keyboard
    foreach ($vk in $vkNames.Keys) {
        $down = (([HidProbeNative]::GetAsyncKeyState($vk)) -band 0x8000) -ne 0
        $was = $prevKeys[$vk]
        if ($down -and -not $was) { Add-Event ("KEY DOWN  {0} (vk=0x{1:X2})" -f $vkNames[$vk], $vk) }
        elseif (-not $down -and $was) { Add-Event ("KEY UP    {0} (vk=0x{1:X2})" -f $vkNames[$vk], $vk) }
        $prevKeys[$vk] = $down
    }

    # Mouse movement + buttons
    [void][HidProbeNative]::GetCursorPos([ref]$cursor)
    if ($cursor.X -ne $prevMouseX -or $cursor.Y -ne $prevMouseY) {
        Add-Event ("MOUSE MOVE dx={0} dy={1} -> X={2} Y={3}" -f ($cursor.X - $prevMouseX), ($cursor.Y - $prevMouseY), $cursor.X, $cursor.Y)
        $prevMouseX = $cursor.X; $prevMouseY = $cursor.Y
    }
    $buttons = 0
    if ((([HidProbeNative]::GetAsyncKeyState(0x01)) -band 0x8000) -ne 0) { $buttons = $buttons -bor 1 }
    if ((([HidProbeNative]::GetAsyncKeyState(0x02)) -band 0x8000) -ne 0) { $buttons = $buttons -bor 2 }
    if ((([HidProbeNative]::GetAsyncKeyState(0x04)) -band 0x8000) -ne 0) { $buttons = $buttons -bor 4 }
    if ($buttons -ne $prevButtons) {
        Add-Event ("MOUSE BUTTONS 0x{0:X2}" -f $buttons)
        $prevButtons = $buttons
    }

    # Gamepads
    $seenPads = @{}
    foreach ($pad in (Read-Joysticks)) {
        $key = "pad$($pad.Id)"
        $seenPads[$key] = $true
        $sig = Pad-Signature $pad
        if ($prevPads[$key] -ne $sig) {
            if (-not $prevPads.ContainsKey($key)) { Add-Event ("GAMEPAD {0} appeared: {1}" -f $key, $sig) }
            else { Add-Event ("GAMEPAD {0} {1}" -f $key, $sig) }
            $prevPads[$key] = $sig
        }
    }
    foreach ($key in @($prevPads.Keys)) {
        if (-not $seenPads.ContainsKey($key)) {
            Add-Event ("GAMEPAD {0} disappeared" -f $key)
            $prevPads.Remove($key)
        }
    }

    Start-Sleep -Milliseconds $IntervalMs
}

Add-Event ("sampling finished: {0} change events" -f $events.Count)
if ($OutFile) {
    $events | Set-Content -Path $OutFile -Encoding UTF8
    Write-Host "written: $OutFile"
}
