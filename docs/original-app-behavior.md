# Behavior of the original Weiview app

This document gives the results of a static analysis of Weiview 1.0.29.

- File: `bad_app/Weiview_1.0.29_Apkpure.apk`
- Package: `com.weiview`
- SHA-256: `0c500a62…a4249`

The analysis used REA (with its JADX and Apktool providers), the `jadx` CLI, and Ghidra on `librcipcam.so`.
Nobody ran the APK.

## Summary

The analysis found no spyware behavior.
The app does not collect contacts, SMS, accounts, device IDs or lists of installed apps.
The app does not contain these items:

- Ad SDKs or analytics SDKs
- Background services
- Boot receivers
- Dynamic code loads
- Shell commands

**When the app was installed but not open, it did nothing.**
When the app was open, it did these steps:

1. It found the camera on the local Wi-Fi network.
2. It received video from `192.168.1.1:80`.
3. It saved snapshots and recordings in `DCIM/xrcam/`.

The Play Store ban and the Play Protect removal probably come from the libraries in the app.
They probably do not come from the operations of Weiview itself.
The app is a thin layer on a generic Chinese IP camera SDK (SoSoCam/ReeCam).
That SDK contains a cloud stack and a P2P stack with bad security practices.
It also declares many permissions that are too broad or obsolete.
The sections that follow give the probable causes.

NOTE: Google does not publish the exact cause of a detection.
Thus, the causes in this document are an inference.

## Operations on the phone

| When | What | Evidence |
| --- | --- | --- |
| App opened | It asked for the location and storage permissions. Android requires the location permission to read Wi-Fi SSIDs. | `WelcomeActivity.getPermissions()` |
| App opened | It got a Wi-Fi lock and a multicast lock, and monitored connectivity changes. It did this only while the app was in operation. | `IPCamMgr.init()` |
| App opened | It loaded `librcipcam.so` and initialized the SDK. This sets only local state. The P2P layer gets an empty server list, thus it sends nothing. | Ghidra: `RCIPCam_Init` → `init_rc_ipcam_lib` → `PPCS_Initialize("")` |
| Camera search | It sent LAN broadcast and multicast discovery packets. If it found no camera, it opened the system Wi-Fi picker. It did not change the network automatically. | `IPCamMgr.StartDiscoverCameras`, `ConnFailDialog` (`PICK_WIFI_NETWORK`) |
| Connected | It operated in "offline mode", with a direct connection to port 80 at the IP address of the camera. | `WelcomeActivity` (`g_offline_mode = true`, `start_connect(current_ip, 80, …)`) |
| Snapshot or recording | It wrote files to `DCIM/xrcam/…` on shared storage. | `Storage.get_album_folder2()` (`APPNAME = "xrcam"`) |

On the Weiview code path, the app did **not** do these operations:

- **It did not connect to a cloud server.**
  The SDK contains a cloud client (`webservices.sosocam.com`, `download.reecam.cn`, `push.reecam.cn`).
  It also contains a P2P relay client.
  The Weiview camera ID (`WeiView_Cam_ID`) does not agree with the P2P ID patterns in `IPCam.get_p2p_svr()`.
  Thus, the SDK selects no P2P server.
  The code in `com.weiview` does not call the cloud login, account or push APIs in `WebService`.
  Only its own retry callback calls the firmware update check, and nothing starts that callback.
- **It did not open ports on the router.**
  The native library contains miniupnpc (UPnP port mapping).
  Only `upnp_thread_handler` calls `upnp_add_port_mapping`.
  No code in the disassembly starts that thread.
  That code is for the camera side.
- **It did not operate in the background.**
  The manifest declares no services, receivers or alarms.
  The full app is five activities and a FileProvider that is not exported.

## Probable causes of the ban and the Play Protect flag

1. **The P2P and NAT traversal SDK.**
   `librcipcam.so` contains a static copy of the CS2 Network "PPPP/PPCS" P2P library.
   Examples are `PPCS_ConnectByServer`, `PPPP_Proto_Send_P2PReq` and `gP2PServerAddr`.
   The library also contains relay sessions, tunnel sessions and a local TCP listener (`new_async_tcp_socket_4_p2pagent_listen`).
   It also contains UPnP IGD port mapping and pinhole code (`UPNP_AddPortMapping`, `UPNP_AddPinhole`).
   Many vulnerabilities in cheap IP cameras come from this SDK family.
   Scanners flag apps that can open phone or router ports to the internet.
   They do this also when the app does not use that code path.
2. **The cloud client is not secure.**
   The `WebService` login sends the user name and the password as URL query parameters (`client.php?act=login&username=…&password=…`).
   The native HTTP client sends a false User-Agent (`myclient/1.0 me@null.net`).
   Weiview does not use this code, but the code is in the APK.
3. **The permissions are too broad or obsolete.**
   The app declares these permissions and attributes:
   - `SYSTEM_ALERT_WINDOW` (draw over other apps)
   - `MOUNT_UNMOUNT_FILESYSTEMS`
   - `READ_OWNER_DATA` and `WRITE_OWNER_DATA`
   - `ACCESS_LOCATION_EXTRA_COMMANDS`
   - `requestLegacyExternalStorage`

   The app code does not use the overlay permission or the mount permission.
   Play policy rejects sensitive permissions that the developer does not declare and explain.
   A draw-over-apps permission with no use is a usual sign of malware.
4. **The developer abandoned the app.**
   The app targets SDK 30 and uses the old `android.support` libraries.
   Google Play hides or removes apps that do not meet the target API and policy deadlines.
   Thus, the removal can be partly administrative.

## Items to remove

Probably, you do not have to remove anything.
When you uninstall the app, all of its code goes away.
These items stay:

- Photos and videos in `DCIM/xrcam/`, and possibly an empty `DCIM/UCCAM/` directory.
  These are your files, and they stay after you uninstall the app.
- A saved Wi-Fi network for the access point of the camera, if you connected to it.
  You can remove it in the Wi-Fi settings.
- The app had the location permission while it was installed.
  The app did not connect to a server, thus no location data went off the phone.

## Limits of the analysis

- The analysis was static only.
  Nobody recorded the runtime behavior.
  But a server-side change cannot change the behavior of the app.
  The reason is that the app downloads no code or configuration that changes its logic.
- The analysis of the native library examined only the UPnP, P2P and HTTP code paths.
  It did not examine each function.
  Most of the library is standard FFmpeg, OpenSSL, libevent, WebRTC AEC and miniupnpc.
- The library contains the IP addresses `18.244.0.188`, `2001:4860:b002::68` and `1.2.3.4`.
  These are standard libevent constants for interface detection.
  The library does not connect to them.
