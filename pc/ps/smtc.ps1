# NowPlaying: bridge to Windows media sessions (System Media Transport Controls).
# Runs as a long-lived child process of the app.
#   stdout: one JSON object per line  {t:'sessions'|'windows'|'error', ...}
#   stdin : one JSON command per line {cmd:'toggle'|'play'|'pause'|'next'|'prev'|'seek', id:'<app id>', pos:<ms>}
#           or, for a plain window without a media session: {cmd:..., hwnd:<window handle>}
# Requires Windows 10 1809+ and Windows PowerShell 5.1 (powershell.exe).
# Keep this file ASCII-only.

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Emit($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 6))
    [Console]::Out.Flush()
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    Add-Type -AssemblyName System.Drawing
    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
    $null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
    Add-Type -Namespace NP -Name Win -MemberDefinition '[DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);'

    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

    function Await($op, $type) {
        $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
        [void]$task.Wait(-1)
        $task.Result
    }

    $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
    $propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
    $mgr = Await ($mgrType::RequestAsync()) $mgrType

    $artCache = @{}

    function ReadThumb($props) {
        try {
            $ref = $props.Thumbnail
            if (-not $ref) { return $null }
            $stream = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
            try {
                $size = [uint32]$stream.Size
                if ($size -eq 0 -or $size -gt 8000000) { return $null }
                try {
                    $reader = New-Object Windows.Storage.Streams.DataReader($stream.GetInputStreamAt(0))
                    [void](Await ($reader.LoadAsync($size)) ([uint32]))
                    $bytes = New-Object byte[] $size
                    $reader.ReadBytes($bytes)
                    $reader.DetachStream() | Out-Null
                    return [Convert]::ToBase64String($bytes)
                } catch {
                    $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                    $ms = New-Object System.IO.MemoryStream
                    $net.CopyTo($ms)
                    if ($ms.Length -eq 0) { return $null }
                    return [Convert]::ToBase64String($ms.ToArray())
                }
            } finally {
                $stream.Dispose()
            }
        } catch {
            return $null
        }
    }

    # Program icon on a dark square: used as the cover when an app gives no cover of its own.
    function IconB64($path) {
        try {
            $ico = [System.Drawing.Icon]::ExtractAssociatedIcon($path)
            if (-not $ico) { return $null }
            $bmp = New-Object System.Drawing.Bitmap 256, 256
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.SmoothingMode = 'AntiAlias'
            $g.InterpolationMode = 'HighQualityBicubic'
            $rect = New-Object System.Drawing.Rectangle 0, 0, 256, 256
            $c1 = [System.Drawing.Color]::FromArgb(255, 62, 70, 104)
            $c2 = [System.Drawing.Color]::FromArgb(255, 20, 22, 34)
            $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $c1, $c2, [single]45)
            $g.FillRectangle($br, $rect)
            $g.DrawImage($ico.ToBitmap(), 64, 64, 128, 128)
            $ms = New-Object System.IO.MemoryStream
            $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
            $g.Dispose(); $br.Dispose(); $bmp.Dispose()
            return [Convert]::ToBase64String($ms.ToArray())
        } catch {
            return $null
        }
    }

    function ProcPath($p) {
        try { return $p.MainModule.FileName } catch { return $null }
    }

    # Session id (app user model id) -> process name, e.g. "Spotify.exe" -> "Spotify"
    function IdToProcName($id) {
        $n = [string]$id
        if ($n.Contains('!')) { $n = $n.Split('!')[-1] }
        $n = $n -replace '\.exe$', ''
        if ($n.Contains('.')) { $n = $n.Split('.')[-1] }
        return $n
    }

    function SessionIcon($id) {
        try {
            $n = IdToProcName $id
            if (-not $n) { return $null }
            foreach ($p in @(Get-Process -Name $n -ErrorAction SilentlyContinue)) {
                $path = ProcPath $p
                if ($path) {
                    $b = IconB64 $path
                    if ($b) { return $b }
                }
            }
        } catch { }
        return $null
    }

    function SessionInfo($s) {
        $props = Await ($s.TryGetMediaPropertiesAsync()) $propsType
        $pb = $s.GetPlaybackInfo()
        $tl = $s.GetTimelineProperties()
        $ctl = $pb.Controls
        $status = [string]$pb.PlaybackStatus
        $posMs = 0.0
        $durMs = 0.0
        try {
            $startMs = $tl.StartTime.TotalMilliseconds
            $durMs = [math]::Max(0.0, $tl.EndTime.TotalMilliseconds - $startMs)
            $posMs = $tl.Position.TotalMilliseconds - $startMs
            if ($status -eq 'Playing') {
                $elapsed = ([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime).TotalMilliseconds
                if ($elapsed -gt 0 -and $elapsed -lt 3600000) { $posMs += $elapsed }
            }
            if ($durMs -gt 0 -and $posMs -gt $durMs) { $posMs = $durMs }
            if ($posMs -lt 0) { $posMs = 0.0 }
        } catch { }

        $id = [string]$s.SourceAppUserModelId
        $title = [string]$props.Title
        $artist = [string]$props.Artist
        $album = [string]$props.AlbumTitle
        $key = $title + '|' + $artist + '|' + $album

        $info = @{
            id       = $id
            title    = $title
            artist   = $artist
            album    = $album
            playing  = ($status -eq 'Playing')
            position = [long]$posMs
            duration = [long]$durMs
            canSeek  = [bool]$ctl.IsPlaybackPositionEnabled
        }

        if ($artCache.ContainsKey($id) -and $artCache[$id] -eq $key) {
            $info.artSame = $true
        } else {
            $thumb = ReadThumb $props
            if ($thumb) {
                $artCache[$id] = $key
                $info.art = $thumb
            } elseif (-not $props.Thumbnail) {
                # the app really has no cover: use the program icon, remember it, do not retry every second
                $artCache[$id] = $key
                $icon = SessionIcon $id
                if ($icon) { $info.art = $icon } else { $info.artSame = $false }
            } else {
                # cover exists but could not be read yet: try again on the next pass
                $info.artSame = $false
            }
        }
        return $info
    }

    function PublishSessions {
        $list = New-Object System.Collections.Generic.List[object]
        $alive = @{}
        foreach ($s in $mgr.GetSessions()) {
            try {
                $info = SessionInfo $s
                $alive[$info.id] = $true
                $list.Add($info)
            } catch { }
        }
        foreach ($k in @($artCache.Keys)) { if (-not $alive.ContainsKey($k)) { $artCache.Remove($k) } }
        Emit @{ t = 'sessions'; list = @($list.ToArray()) }
    }

    $iconSent = @{}

    function PublishWindows {
        $list = New-Object System.Collections.Generic.List[object]
        $alive = @{}
        foreach ($p in (Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle })) {
            $w = @{ pid = $p.Id; name = $p.ProcessName; title = $p.MainWindowTitle; hwnd = [long]$p.MainWindowHandle }
            $alive[$p.Id] = $true
            if (-not $iconSent.ContainsKey($p.Id)) {
                $iconSent[$p.Id] = $true
                $path = ProcPath $p
                if ($path) {
                    $b = IconB64 $path
                    if ($b) { $w.art = $b }
                }
            }
            $list.Add($w)
        }
        foreach ($k in @($iconSent.Keys)) { if (-not $alive.ContainsKey($k)) { $iconSent.Remove($k) } }
        Emit @{ t = 'windows'; list = @($list.ToArray()) }
    }

    function RunCommand($c) {
        if ($c.hwnd) {
            # plain window: classic WM_APPCOMMAND media commands (play/pause 14, next 11, previous 12, play 46, pause 47)
            $code = switch ([string]$c.cmd) { 'toggle' { 14 } 'play' { 46 } 'pause' { 47 } 'next' { 11 } 'prev' { 12 } default { 0 } }
            if ($code -gt 0) {
                $h = [IntPtr][long]$c.hwnd
                [void][NP.Win]::PostMessage($h, 0x319, $h, [IntPtr]([long]$code -shl 16))
            }
            return
        }
        $target = $null
        foreach ($x in $mgr.GetSessions()) {
            if ($x.SourceAppUserModelId -eq [string]$c.id) { $target = $x; break }
        }
        if (-not $target) { return }
        switch ([string]$c.cmd) {
            'toggle' { [void](Await ($target.TryTogglePlayPauseAsync()) ([bool])) }
            'play'   { [void](Await ($target.TryPlayAsync()) ([bool])) }
            'pause'  { [void](Await ($target.TryPauseAsync()) ([bool])) }
            'next'   { [void](Await ($target.TrySkipNextAsync()) ([bool])) }
            'prev'   { [void](Await ($target.TrySkipPreviousAsync()) ([bool])) }
            'seek'   { [void](Await ($target.TryChangePlaybackPositionAsync([long]([double]$c.pos * 10000))) ([bool])) }
        }
    }

    # stdin is read in a separate runspace so the main loop never blocks
    $queue = New-Object 'System.Collections.Concurrent.ConcurrentQueue[string]'
    $reader = [powershell]::Create()
    [void]$reader.AddScript({
        param($q)
        while ($true) {
            $line = [Console]::In.ReadLine()
            if ($null -eq $line) { break }
            $q.Enqueue($line)
        }
    }).AddArgument($queue)
    [void]$reader.BeginInvoke()

    $tick = 0
    $force = $false
    while ($true) {
        if ($reader.InvocationStateInfo.State -ne 'Running') { break }
        $line = $null
        while ($queue.TryDequeue([ref]$line)) {
            try { RunCommand ($line | ConvertFrom-Json) } catch { }
            $force = $true
        }
        if ($force -or ($tick % 4 -eq 0)) {
            try { PublishSessions } catch { Emit @{ t = 'error'; msg = $_.Exception.Message } }
            $force = $false
        }
        if ($tick % 12 -eq 0) {
            try { PublishWindows } catch { }
        }
        $tick++
        Start-Sleep -Milliseconds 250
    }
} catch {
    Emit @{ t = 'error'; msg = $_.Exception.Message }
    exit 1
}
