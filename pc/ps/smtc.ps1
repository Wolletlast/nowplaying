# NowPlaying: bridge to Windows media sessions (System Media Transport Controls).
# Runs as a long-lived child process of the app.
#   stdout: one JSON object per line  {t:'sessions'|'windows'|'error', ...}
#   stdin : one JSON command per line {cmd:'toggle'|'play'|'pause'|'next'|'prev'|'seek', id:'<app id>', pos:<ms>}
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
    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]

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
                $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)
                $ms = New-Object System.IO.MemoryStream
                $net.CopyTo($ms)
                if ($ms.Length -eq 0 -or $ms.Length -gt 8000000) { return $null }
                return [Convert]::ToBase64String($ms.ToArray())
            } finally {
                $stream.Dispose()
            }
        } catch {
            return $null
        }
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
                # the app really has no cover: remember that, do not retry every second
                $artCache[$id] = $key
                $info.artSame = $false
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

    function PublishWindows {
        $list = New-Object System.Collections.Generic.List[object]
        foreach ($p in (Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle })) {
            $list.Add(@{ pid = $p.Id; name = $p.ProcessName; title = $p.MainWindowTitle })
        }
        Emit @{ t = 'windows'; list = @($list.ToArray()) }
    }

    function RunCommand($c) {
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
