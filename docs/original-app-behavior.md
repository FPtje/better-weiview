# What the original Weiview app did

This is a static analysis of Weiview 1.0.29 (`bad_app/Weiview_1.0.29_Apkpure.apk`, package
`com.weiview`, SHA-256 `0c500a62…a4249`). It was done with REA (JADX and Apktool
providers), the `jadx` CLI, and Ghidra on `librcipcam.so`. The APK was never run.

## Short answer

No spyware-style behavior was found. The app collects no contacts, SMS, accounts, device
IDs or installed-app lists. It has no ad or analytics SDKs, no background services, no boot
receivers, no dynamic code loading and no shell commands. **While installed, it did nothing
unless you opened it.** When open, it found the camera on the local Wi-Fi, streamed video
from `192.168.1.1:80`, and saved snapshots and recordings to `DCIM/xrcam/`.

The Play Store ban and the Play Protect removal most likely come from what the app
*bundles*, not from what Weiview itself runs. The app is a thin skin over a generic Chinese
IP-camera SDK (SoSoCam / ReeCam). That SDK carries a cloud and P2P stack with poor security
practices, plus a pile of over-broad, outdated permissions. The sections below list the
likely triggers. Google does not publish the exact detection reason, so this part is
inference.

## What ran on your phone

| When | What | Evidence |
| --- | --- | --- |
| App opened | Asked for location and storage permissions. Location is needed on Android to read Wi-Fi SSIDs. | `WelcomeActivity.getPermissions()` |
| App opened | Took a Wi-Fi lock and a multicast lock and listened for connectivity changes, while the app was alive. | `IPCamMgr.init()` |
| App opened | Loaded `librcipcam.so` and initialised the SDK. This sets up local state only. The P2P layer is initialised with an empty server list, so nothing goes out. | Ghidra: `RCIPCam_Init` → `init_rc_ipcam_lib` → `PPCS_Initialize("")` |
| Searching for the camera | Sent LAN broadcast/multicast discovery packets. If no camera was found, it opened the system Wi-Fi picker. It does not switch networks by itself. | `IPCamMgr.StartDiscoverCameras`, `ConnFailDialog` (`PICK_WIFI_NETWORK`) |
| Connected | Ran in "offline mode", connecting straight to the camera's IP on port 80. | `WelcomeActivity` (`g_offline_mode = true`, `start_connect(current_ip, 80, …)`) |
| Snapshot/record | Wrote files to `DCIM/xrcam/…` on shared storage. | `Storage.get_album_folder2()` (`APPNAME = "xrcam"`) |

What it did **not** do on the Weiview code path:

- **Contact any cloud server.** The SDK holds a cloud client (`webservices.sosocam.com`,
  `download.reecam.cn`, `push.reecam.cn`) and a P2P relay client. Weiview's camera ID
  (`WeiView_Cam_ID`) matches none of the P2P ID patterns in `IPCam.get_p2p_svr()`, so no
  P2P server is selected. The cloud login, account and push APIs in `WebService` are never
  called from `com.weiview`. The only caller of the firmware update check is its own retry
  callback, which never gets started.
- **Open ports on your router.** The native lib contains miniupnpc (UPnP port mapping).
  `upnp_add_port_mapping` is reachable only from `upnp_thread_handler`, and no code in the
  disassembly starts that thread. That code is meant for the camera side.
- **Run in the background.** The manifest declares no services, receivers or alarms. Five
  activities and a non-exported FileProvider make up the whole app.

## Likely reasons for the ban and Play Protect flag

1. **Bundled P2P/NAT-traversal SDK.** `librcipcam.so` statically links the CS2 Network
   "PPPP/PPCS" P2P library (`PPCS_ConnectByServer`, `PPPP_Proto_Send_P2PReq`,
   `gP2PServerAddr`), relay and tunnel sessions, and a local TCP listener
   (`new_async_tcp_socket_4_p2pagent_listen`). It also includes UPnP IGD port mapping and
   pinhole code (`UPNP_AddPortMapping`, `UPNP_AddPinhole`). This SDK family has a long
   record of vulnerabilities in cheap IP cameras. Scanners flag apps that can open phone
   or router ports to the internet, whether or not a given app uses that path.
2. **Insecure cloud client.** The `WebService` login sends username and password as URL
   query parameters (`client.php?act=login&username=…&password=…`). The native HTTP client
   uses a fake User-Agent (`myclient/1.0 me@null.net`). This code is unused by Weiview but
   ships in the APK.
3. **Over-broad and obsolete permissions.** `SYSTEM_ALERT_WINDOW` (draw over other apps),
   `MOUNT_UNMOUNT_FILESYSTEMS`, `READ_OWNER_DATA`/`WRITE_OWNER_DATA`,
   `ACCESS_LOCATION_EXTRA_COMMANDS` and `requestLegacyExternalStorage` are declared. Overlay
   and mount are never used in the app code. Play policy rejects undeclared or unjustified
   sensitive permissions, and a draw-over-apps permission with no use case is a classic
   malware signal.
4. **Abandoned app.** It targets SDK 30 and uses the old `android.support` libraries.
   Google Play hides or removes apps that stop meeting target-API and policy deadlines, so
   the delisting may be partly administrative.

## Is there anything to clean up?

Probably not. Uninstalling the app removes everything it ran. The leftovers are:

- Photos and videos in `DCIM/xrcam/` (and possibly an empty `DCIM/UCCAM/`), which are your
  own files and stay after uninstall.
- A saved Wi-Fi network for the camera's access point, if you joined it. Forget it in
  Wi-Fi settings if you like.
- The app had location permission while installed. Because it never contacted a server,
  no location data left the phone.

## Caveats

- Static analysis only. Runtime behavior was not captured. A server-side change could not
  have altered behavior, though, because the app downloads no code or config that changes
  its logic.
- The native lib was analyzed only for UPnP/P2P/HTTP reachability, not function by
  function. It is mostly stock FFmpeg, OpenSSL, libevent, WebRTC AEC and miniupnpc.
  The hardcoded IPs `18.244.0.188`, `2001:4860:b002::68` and `1.2.3.4` are stock libevent
  constants for interface detection and are never contacted.
