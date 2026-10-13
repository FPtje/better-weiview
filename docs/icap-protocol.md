# Weiview ear cam "icap" protocol

Reverse engineered from Weiview 1.0.29 (`librcipcam.so`, SoSoCam/ReeCam SDK) by static
analysis, and verified live against the camera (firmware `86.12.7.4.83`). This spec is the
only input for the clean-room implementations in `tools/icap_client.py` and the Android
app. No vendor code is reused.

## Transport

- The camera runs its own access point (`weiview-XXXX`, open) and sits at `192.168.1.1`.
- Everything goes over **one TCP connection to port 80**. That port also serves the Boa
  HTTP CGI API, and the server tells the two apart by the first bytes the client sends.
- `GET /get_properties.cgi?user=admin&pwd=` returns `firmware_ver='…'; voudp=0;`. With
  `voudp=0`, video also comes over TCP. The UDP video path (`voudp`) is not covered here.
- Credentials are user `admin` with an empty password.

## Packet framing

Each message, in both directions:

| offset | size | field |
| --- | --- | --- |
| 0 | 4 | magic `05 33 8B 11` |
| 4 | 2 | command, u16 little-endian |
| 6 | 4 | payload length N, u32 little-endian |
| 10 | N | payload |

A receiver resynchronises by scanning for the magic.

## Cipher

The login strings are encrypted with Blowfish in ECB mode. The camera's variant differs
from standard Blowfish in three ways:

1. **The P-array starts as all zeros**, not the pi digits. The key is XORed in the
   standard way (big-endian 4-byte groups that wrap around the key), followed by the usual
   encrypt-zero-block re-keying of P and then S.
2. **The S-boxes are the standard pi-derived tables except for 5 entries:**
   `S0[227]=0x56DFA6AA`, `S0[250]=0xFF340528`, `S2[25]=0x55FA3941`,
   `S2[187]=0x915F958E`, `S3[23]=0x42D04DC6`.
3. **Each 8-byte block is two little-endian u32 words** `(L, R)`.

The rounds themselves are standard (16 rounds; F = ((S0[a]+S1[b])^S2[c])+S3[d]). The key is
the ASCII string `hello kitty and kgb/cia 2011 COPYRIGHT@REECAM 5460` (50 bytes). The SDK
accepts a custom key, but the app always passes null, so this default is used.

**String block:** for a string s, the block is `s`, a NUL byte, then filler up to
`(len(s)+8) & ~7` bytes. The original app fills with random bytes; zeros also work.
The whole block is encrypted.

**Test vector (real capture):** a login1 response payload
`12 c2bbdbce9b2664e4ff5bc380b9e4f463b38e1876287b217e` decrypts to `26:24:1c:10:0d:ad\0`
plus padding. That string is the camera ID, which is its MAC address.

## Session

```
client                                   camera
  | cmd 0x00 LOGIN1_REQ   (empty)          |
  |--------------------------------------->|
  |          cmd 0x01 LOGIN1_RESP          |
  |<---------------------------------------|  u8 n, Blowfish(block of camera id), n = len+1
  | cmd 0x02 LOGIN2_REQ                    |
  |--------------------------------------->|  u8 len(user)+1, Blowfish(block(user)),
  |                                        |  u8 len(pwd)+1,  Blowfish(block(pwd))
  |          cmd 0x03 LOGIN2_RESP          |
  |<---------------------------------------|  u8 result, u32 extra   (5 bytes)
  | cmd 0x2F P2P_TYPE_NOTIFY  u8 0         |
  | cmd 0x0E PLAY_VIDEO       u32 stream   |  stream 0 = main
  | cmd 0x1C SET_VIDEO_PERF   u8 3, u8 1   |
  |--------------------------------------->|
  |     cmd 0x11 / 0x25 / 0x34 / 0x35      |  video frames, continuously
  |<---------------------------------------|
```

**Login result codes** (`result` byte → SDK error): 0 OK, 1 BAD_AUTH, 2 MAX_SESSIONS,
3 INTERNAL, 4 BAD_PARAM, 5 FORBIDDEN, 6 OPERATION_FAIL, 7 BAD_STATUS. With BAD_AUTH,
`extra` carries a retry-delay hint. With OK, observed values are `extra = 131071` and
a camera ID equal to its MAC address.

A 60-second stream ran without any keepalive from the client. The camera keeps sending
for as long as the TCP connection is open.

Other commands seen in the SDK, not needed for live view: 0x06/0x08 string results,
0x0A/0x0C result codes, 0x0D property/status push, 0x0F, 0x12 stop video, 0x19 speak
(audio up), 0x1A PTZ, 0x29 play SD record, 0x2D/0x2E flow control, 0x30 (an IR-video
request).

## Video frames

Frame header fields (all little-endian):

| offset | size | field |
| --- | --- | --- |
| 0 | 1 | codec: 0 = MJPEG, 2 = H.264, 3 = H.265 |
| 1 | 1 | resolution id: 6 VGA, 9 480×480, 11 720p, 15 640×360, 19 1280×960, 21 1080p, 22 1600×1200 |
| 2 | 1 | unknown (0 observed) |
| 3 | 1 | unknown (0 observed) |
| 4 | 1 | key frame flag (1 for every MJPEG frame) |
| 5 | 4 | tick, in units of 10 ms |
| 9 | 4 | unknown (0 observed) |
| 13 | 2+2+2 | **ext. headers only:** accelerometer x, y, z (s16) |
| 13 or 19 | 4 | data length L |

- **0x11 VIDEO:** 17-byte header, then L data bytes.
- **0x34 VIDEO3:** 23-byte extended header, then L data bytes.
- **0x25 VIDEO2 / 0x35 VIDEO4 (fragmented):** each payload starts with a flags byte.
  - bit0 set: a new frame starts, and the header follows (17 bytes for 0x25, 23 for
    0x35).
  - bit1 set: data bytes follow.
  - Append the data until L bytes have been collected; that completes the frame.

**Observed on this camera:** cmd 0x35, codec 0 (MJPEG, baseline JPEG), resolution 9
(480×480), about 19 fps, about 14.5 KB per frame. The accelerometer fields change as the
camera moves.
