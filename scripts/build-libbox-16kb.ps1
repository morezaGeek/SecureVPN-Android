param(
    [Parameter(Mandatory=$true)][string]$CoreSource,
    [Parameter(Mandatory=$true)][string]$NativeTools,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$JavaHome
)
$ErrorActionPreference='Stop'
# CGO tokenizes CC itself; an NDK path containing spaces must use its Windows short path.
if ($NativeTools.Contains(' ')) {
    if (-not ('SecureVpnBuild.NativePath' -as [type])) {
        Add-Type 'using System; using System.Text; using System.Runtime.InteropServices; namespace SecureVpnBuild { public static class NativePath { [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] public static extern uint GetShortPathName(string path, StringBuilder result, uint length); } }'
    }
    $shortPath=New-Object System.Text.StringBuilder 32768
    if ([SecureVpnBuild.NativePath]::GetShortPathName($NativeTools,$shortPath,32768) -eq 0) { throw 'Cannot resolve NDK short path' }
    $NativeTools=$shortPath.ToString()
    if ($NativeTools.Contains(' ')) { throw 'Use a native toolchain directory without spaces' }
}
$env:GOPATH=Join-Path $NativeTools 'gopath'
$env:GOMODCACHE=Join-Path $NativeTools 'gomodcache'
$env:GOCACHE=Join-Path $NativeTools 'gocache'
$env:GOTOOLCHAIN='go1.26.8'
$env:ANDROID_HOME=$AndroidSdk
$env:ANDROID_NDK_HOME=Join-Path $NativeTools 'android-ndk-r30'
$env:JAVA_HOME=$JavaHome
$env:PATH=(Join-Path $NativeTools 'go\bin')+';'+(Join-Path $env:GOPATH 'bin')+';'+(Join-Path $JavaHome 'bin')+';'+$env:PATH
# Rebuild source using NDK r30 and explicit RELRO/page alignment; do not patch ELF headers.
$env:CGO_LDFLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
$tags='with_gvisor,with_quic,with_utls,with_xhttp,with_lx_command,with_openconnect,badlinkname,tfogo_checklinkname0'
$linkFlags='-X github.com/sagernet/sing-box/constant.Version=1.14.2-lx.11-securevpn.16k -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -R 16384 -w -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"'
Push-Location -LiteralPath $CoreSource
try {
    & (Join-Path $env:GOPATH 'bin\gomobile.exe') bind -v -o libbox-securevpn.aar -target android -androidapi 26 -javapkg io.nekohasekai -libname box -trimpath -buildvcs=false -tags $tags -ldflags $linkFlags ./experimental/libbox
    if($LASTEXITCODE -ne 0) { throw 'libbox native source build failed' }
} finally { Pop-Location }
