# Weiview ear cam "icap" protocol

This specification comes from static analysis of Weiview 1.0.29.
The analysis examined `librcipcam.so`, which is part of the SoSoCam/ReeCam SDK.
Live tests on the camera (firmware `86.12.7.4.83`) confirmed the specification.

This specification is the only input for the clean-room implementations.
These are `tools/icap_client.py` and the Android app.
The implementations do not use vendor code.

## Transport

- The camera has its own open access point (`weiview-XXXX`).
  The address of the camera is `192.168.1.1`.
- All traffic uses **one TCP connection to port 80**.
  The Boa HTTP CGI API also uses this port.
  The server uses the first bytes from the client to identify the protocol.
- The request `GET /get_properties.cgi?user=admin&pwd=` returns `firmware_ver='…'; voudp=0;`.
  When `voudp=0`, the video also uses TCP.
  This specification does not include the UDP video path (`voudp`).
- The user name is `admin`.
  The password is empty.

## Packet framing

Each message in each direction has this layout:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | Magic `05 33 8B 11` |
| 4 | 2 | Command, u16 little-endian |
| 6 | 4 | Payload length N, u32 little-endian |
| 10 | N | Payload |

If the receiver loses the packet boundary, it finds the next magic to synchronize again.

## Cipher

The login strings use Blowfish in ECB mode.
The variant of the camera is different from standard Blowfish in three ways:

1. **All values in the initial P-array are zero.**
   Standard Blowfish uses the digits of pi.
   The key operation is standard.
   Each P entry gets an XOR with the next 4 key bytes as a big-endian word.
   The key bytes wrap around.
   Then the standard encryption of a zero block replaces P, and then S.
2. **The S-boxes are the standard tables from pi, but 5 entries are different:**
   `S0[227]=0x56DFA6AA`, `S0[250]=0xFF340528`, `S2[25]=0x55FA3941`,
   `S2[187]=0x915F958E`, `S3[23]=0x42D04DC6`.
3. **Each 8-byte block is two little-endian u32 words** `(L, R)`.

The rounds are standard: 16 rounds, with F = ((S0[a]+S1[b])^S2[c])+S3[d].
The key is the ASCII string `hello kitty and kgb/cia 2011 COPYRIGHT@REECAM 5460` (50 bytes).
The SDK accepts a custom key.
The app always supplies null, thus the SDK uses this default key.

**String block:** for a string s, the block contains `s`, a NUL byte, and filler bytes.
The block length is `(len(s)+8) & ~7` bytes.
The original app uses random filler bytes.
Zero bytes also work.
The encryption applies to the full block.

**Test vector (real capture):** this is the payload of a login1 response:
`12 c2bbdbce9b2664e4ff5bc380b9e4f463b38e1876287b217e`.
It decrypts to `26:24:1c:10:0d:ad\0` and filler bytes.
That string is the camera ID, which is the MAC address of the camera.

## Session

```text
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

**Login result codes** (the `result` byte and the related SDK error):

| Code | SDK error |
| --- | --- |
| 0 | OK |
| 1 | BAD_AUTH |
| 2 | MAX_SESSIONS |
| 3 | INTERNAL |
| 4 | BAD_PARAM |
| 5 | FORBIDDEN |
| 6 | OPERATION_FAIL |
| 7 | BAD_STATUS |

With BAD_AUTH, `extra` contains a recommended delay before the next attempt.
With OK, the observed value is `extra = 131071`.
The observed camera ID is the MAC address of the camera.

**Keepalive:** during the session, the client must send **cmd 0x04 with an empty payload** at intervals.
The vendor SDK sends it after 30 s with no transmitted data.
Our clients send it each 10 s.
If the SDK receives no data for 40 s, it closes the connection and connects again automatically.
Without keepalives, the camera stops the session.
In one test, a phone session stopped after approximately 90 s.

The SDK also contains these commands.
Live view does not use them.

| Command | Function |
| --- | --- |
| 0x06, 0x08 | String results |
| 0x0A, 0x0C | Result codes |
| 0x0D | Property and status push |
| 0x0F | Unknown |
| 0x12 | Stop video |
| 0x19 | Speak (audio to the camera) |
| 0x1A | PTZ |
| 0x29 | Play an SD card record |
| 0x2D, 0x2E | Flow control |
| 0x30 | Request for IR video |

## Video frames

All fields of the frame header are little-endian:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Codec: 0 = MJPEG, 2 = H.264, 3 = H.265 |
| 1 | 1 | Resolution ID: 6 VGA, 9 480×480, 11 720p, 15 640×360, 19 1280×960, 21 1080p, 22 1600×1200 |
| 2 | 1 | Unknown (0 observed) |
| 3 | 1 | Unknown (0 observed) |
| 4 | 1 | Key frame flag (1 for each MJPEG frame) |
| 5 | 4 | Tick, in units of 10 ms |
| 9 | 4 | Unknown (0 observed) |
| 13 | 2+2+2 | **Extended headers only:** accelerometer x, y, z (s16) |
| 13 or 19 | 4 | Data length L |

- **0x11 VIDEO:** a 17-byte header, then L data bytes.
- **0x34 VIDEO3:** a 23-byte extended header, then L data bytes.
- **0x25 VIDEO2 and 0x35 VIDEO4 (fragments):** each payload starts with a flags byte.
  - If bit 0 is set, a new frame starts.
    The header follows the flags byte (17 bytes for 0x25, 23 bytes for 0x35).
  - If bit 1 is set, data bytes follow.
  - Add the data bytes to the frame until the frame has L bytes.
    Then the frame is complete.

**Observed on this camera:**

- The camera sends cmd 0x35 with codec 0 (MJPEG, baseline JPEG).
- The resolution is 9 (480×480).
- The frame rate is approximately 19 frames per second.
- The size of a frame is approximately 14.5 KB.
- The accelerometer values change when the camera moves.
