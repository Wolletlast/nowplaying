# NowPlaying: list playback devices and switch the default one.
#   audio.ps1 -Action list
#   audio.ps1 -Action set -Id <endpoint GUID, 36 chars>
# Prints one JSON line. Keep this file ASCII-only and C# 5 compatible (Windows PowerShell 5.1).
param(
    [string]$Action = 'list',
    [string]$Id = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Emit($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 5))
    [Console]::Out.Flush()
}

$code = @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace NP
{
    [StructLayout(LayoutKind.Sequential)]
    public struct PropertyKey { public Guid fmtid; public uint pid; }

    [StructLayout(LayoutKind.Sequential)]
    public struct PropVariant
    {
        public ushort vt; public ushort r1; public ushort r2; public ushort r3;
        public IntPtr p1; public IntPtr p2;
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
        [PreserveSig] int Commit();
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int Item(uint index, out IMMDevice device);
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    public class MMDeviceEnumeratorClass { }

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPolicyConfig
    {
        [PreserveSig] int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr format);
        [PreserveSig] int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, int isDefault, IntPtr format);
        [PreserveSig] int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id);
        [PreserveSig] int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr endpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string id, int isDefault, IntPtr defaultPeriod, IntPtr minPeriod);
        [PreserveSig] int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr period);
        [PreserveSig] int GetShareMode([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr mode);
        [PreserveSig] int SetShareMode([MarshalAs(UnmanagedType.LPWStr)] string id, IntPtr mode);
        [PreserveSig] int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string id, int fx, IntPtr key, IntPtr value);
        [PreserveSig] int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string id, int fx, IntPtr key, IntPtr value);
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
        [PreserveSig] int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)] string id, int visible);
    }

    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    public class PolicyConfigClass { }

    public class AudioInfo
    {
        public string Id;
        public string Name;
        public bool IsDefault;
    }

    public static class Audio
    {
        [DllImport("ole32.dll")]
        private static extern int PropVariantClear(ref PropVariant pv);

        public static List<AudioInfo> ListDevices()
        {
            List<AudioInfo> result = new List<AudioInfo>();
            IMMDeviceEnumerator en = (IMMDeviceEnumerator)new MMDeviceEnumeratorClass();

            string defaultId = null;
            IMMDevice def;
            if (en.GetDefaultAudioEndpoint(0, 1, out def) == 0 && def != null)
            {
                def.GetId(out defaultId);
            }

            IMMDeviceCollection col;
            Marshal.ThrowExceptionForHR(en.EnumAudioEndpoints(0, 1, out col));
            uint n;
            col.GetCount(out n);
            for (uint i = 0; i < n; i++)
            {
                IMMDevice d;
                if (col.Item(i, out d) != 0) continue;
                string id;
                d.GetId(out id);
                AudioInfo info = new AudioInfo();
                info.Id = id;
                info.Name = FriendlyName(d);
                if (info.Name == null) info.Name = id;
                info.IsDefault = (id == defaultId);
                result.Add(info);
            }
            return result;
        }

        private static string FriendlyName(IMMDevice d)
        {
            IPropertyStore ps;
            if (d.OpenPropertyStore(0, out ps) != 0) return null;
            PropertyKey key = new PropertyKey();
            key.fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
            key.pid = 14;
            PropVariant v;
            if (ps.GetValue(ref key, out v) != 0) return null;
            try
            {
                if (v.vt == 31) return Marshal.PtrToStringUni(v.p1);
                return null;
            }
            finally
            {
                PropVariantClear(ref v);
            }
        }

        public static void SetDefault(string id)
        {
            IPolicyConfig pc = (IPolicyConfig)new PolicyConfigClass();
            for (int role = 0; role < 3; role++)
            {
                Marshal.ThrowExceptionForHR(pc.SetDefaultEndpoint(id, role));
            }
        }
    }
}
'@

try {
    Add-Type -TypeDefinition $code -Language CSharp

    if ($Action -eq 'set') {
        if ($Id -notmatch '^[0-9a-fA-F\-]{36}$') { throw 'Bad device id' }
        [NP.Audio]::SetDefault('{0.0.0.00000000}.{' + $Id + '}')
        Emit @{ ok = $true }
    } else {
        $items = New-Object System.Collections.Generic.List[object]
        foreach ($d in [NP.Audio]::ListDevices()) {
            $items.Add(@{ id = $d.Id; name = $d.Name; isDefault = $d.IsDefault })
        }
        Emit @{ devices = @($items.ToArray()) }
    }
} catch {
    Emit @{ error = $_.Exception.Message }
    exit 1
}
