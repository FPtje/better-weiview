#!/usr/bin/env python3
"""Laptop prototype of the Weiview ear cam "icap" live-video protocol.

Clean-room reimplementation from docs/icap-protocol.md. Connects over TCP,
logs in, starts the live stream and writes the raw video elementary stream
to a file (MJPEG for this camera; play with: ffplay -f mjpeg out.mjpeg).

Usage: icap_client.py [--host 192.168.1.1] [--port 80] [--iface wlan0]
                      [--stream 0] [--seconds 20] [--out out.h264] [-v]
"""
import argparse
import socket
import struct
import sys
import time

MAGIC = bytes.fromhex("05338b11")
DEFAULT_KEY = b"hello kitty and kgb/cia 2011 COPYRIGHT@REECAM 5460"

CMD_LOGIN1_REQ = 0x00
CMD_LOGIN1_RESP = 0x01
CMD_LOGIN2_REQ = 0x02
CMD_LOGIN2_RESP = 0x03
CMD_KEEPALIVE = 0x04
KEEPALIVE_INTERVAL = 10.0  # vendor SDK: after 30 s without sending
CMD_PLAY_VIDEO = 0x0E
CMD_VIDEO = 0x11
CMD_SET_VIDEO_PERFORMANCE = 0x1C
CMD_VIDEO2_FRAGMENT = 0x25
CMD_P2P_TYPE_NOTIFY = 0x2F
CMD_VIDEO3 = 0x34
CMD_VIDEO4_FRAGMENT = 0x35


# --- Blowfish variant -------------------------------------------------------

def _pi_hex_words(count):
    """First `count` 32-bit words of the fractional hex digits of pi."""
    digits = count * 8 + 16
    prec = 4 * digits

    def arctan_inv(x):
        # arctan(1/x) * 2^prec using integer series
        total = term = (1 << prec) // x
        x2, n, sign = x * x, 1, 1
        while term:
            term //= x2
            n += 2
            sign = -sign
            total += sign * (term // n)
        return total

    pi = 16 * arctan_inv(5) - 4 * arctan_inv(239)  # Machin, scaled by 2^prec
    frac = pi - (3 << prec)
    frac >>= 4 * 16  # drop guard digits
    hexstr = format(frac, "x").rjust(count * 8, "0")[: count * 8]
    return [int(hexstr[i:i + 8], 16) for i in range(0, count * 8, 8)]


_PI = _pi_hex_words(18 + 1024)
_SBOX_INIT = [_PI[18 + 256 * i:18 + 256 * (i + 1)] for i in range(4)]
# The camera's S-boxes carry five single-digit typos relative to pi.
for _box, _idx, _val in ((0, 227, 0x56DFA6AA), (0, 250, 0xFF340528),
                         (2, 25, 0x55FA3941), (2, 187, 0x915F958E),
                         (3, 23, 0x42D04DC6)):
    _SBOX_INIT[_box][_idx] = _val


class CamBlowfish:
    """Blowfish with the camera's quirks.

    * The P-array starts as all zeros (not the pi digits) before the key is
      XORed in; the S-boxes are the standard pi-derived ones.
    * Each 8-byte block is two little-endian 32-bit words (L, R).
    """

    def __init__(self, key: bytes):
        self.S = [list(s) for s in _SBOX_INIT]
        self.P = [0] * 18
        j = 0
        for i in range(18):
            w = 0
            for _ in range(4):
                w = (w << 8) | key[j]
                j = (j + 1) % len(key)
            self.P[i] ^= w
        l = r = 0
        for i in range(0, 18, 2):
            l, r = self._enc(l, r)
            self.P[i], self.P[i + 1] = l, r
        for s in self.S:
            for i in range(0, 256, 2):
                l, r = self._enc(l, r)
                s[i], s[i + 1] = l, r

    def _f(self, x):
        s = self.S
        return ((((s[0][x >> 24] + s[1][(x >> 16) & 0xFF]) & 0xFFFFFFFF)
                 ^ s[2][(x >> 8) & 0xFF]) + s[3][x & 0xFF]) & 0xFFFFFFFF

    def _enc(self, l, r):
        p = self.P
        for i in range(16):
            l ^= p[i]
            r ^= self._f(l)
            l, r = r, l
        l, r = r, l
        r ^= p[16]
        l ^= p[17]
        return l, r

    def _dec(self, l, r):
        p = self.P
        for i in range(17, 1, -1):
            l ^= p[i]
            r ^= self._f(l)
            l, r = r, l
        l, r = r, l
        r ^= p[1]
        l ^= p[0]
        return l, r

    def _apply(self, data: bytes, fn) -> bytes:
        assert len(data) % 8 == 0
        out = bytearray()
        for i in range(0, len(data), 8):
            l, r = struct.unpack_from("<II", data, i)
            out += struct.pack("<II", *fn(l, r))
        return bytes(out)

    def encrypt(self, data: bytes) -> bytes:
        return self._apply(data, self._enc)

    def decrypt(self, data: bytes) -> bytes:
        return self._apply(data, self._dec)


# --- Framing ----------------------------------------------------------------

def pack(cmd: int, payload: bytes = b"") -> bytes:
    return MAGIC + struct.pack("<HI", cmd, len(payload)) + payload


class Reader:
    def __init__(self, sock):
        self.sock = sock
        self.buf = bytearray()

    def _fill(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("camera closed the connection")
            self.buf += chunk

    def packet(self):
        self._fill(10)
        idx = self.buf.find(MAGIC)
        while idx != 0:
            # resync: drop garbage before the next magic
            if idx < 0:
                del self.buf[:-3]
            else:
                del self.buf[:idx]
            self._fill(10)
            idx = self.buf.find(MAGIC)
        cmd, length = struct.unpack_from("<HI", self.buf, 4)
        self._fill(10 + length)
        payload = bytes(self.buf[10:10 + length])
        del self.buf[:10 + length]
        return cmd, payload


def padded_block(s: bytes) -> bytes:
    """len(s)+1 rounded up to 8: the string, a NUL, then filler bytes."""
    raw = s + b"\0"
    size = (len(s) + 8) & ~7
    return raw + bytes(size - len(raw))


def login2_payload(user: bytes, pwd: bytes, bf: CamBlowfish) -> bytes:
    return (bytes([len(user) + 1]) + bf.encrypt(padded_block(user))
            + bytes([len(pwd) + 1]) + bf.encrypt(padded_block(pwd)))


# --- Video frame headers ------------------------------------------------------

def parse_frame_header(h: bytes, extended: bool):
    """Common header fields; `extended` adds three u16 values (accelerometer?)."""
    f = {"b0": h[0], "b1": h[1], "b2": h[2], "b3": h[3], "b4": h[4],
         "u5": struct.unpack_from("<I", h, 5)[0],
         "u9": struct.unpack_from("<I", h, 9)[0]}
    if extended:
        f["x"], f["y"], f["z"] = struct.unpack_from("<hhh", h, 13)
        f["len"] = struct.unpack_from("<I", h, 19)[0]
    else:
        f["len"] = struct.unpack_from("<I", h, 13)[0]
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.1")
    ap.add_argument("--port", type=int, default=80)
    ap.add_argument("--iface", default="wlan0")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pwd", default="")
    ap.add_argument("--stream", type=int, default=0)
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--out", default="out.mjpeg")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()

    bf = CamBlowfish(DEFAULT_KEY)
    s = socket.socket()
    if a.iface:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, a.iface.encode())
    s.settimeout(10)
    s.connect((a.host, a.port))
    rd = Reader(s)

    s.sendall(pack(CMD_LOGIN1_REQ))
    cmd, p = rd.packet()
    if cmd != CMD_LOGIN1_RESP:
        sys.exit(f"unexpected reply to login1: cmd={cmd:#x} {p.hex()}")
    n = p[0]
    blk = bf.decrypt(p[1:1 + ((n + 7) & ~7)])
    camera_id = blk[:n].split(b"\0")[0].decode(errors="replace")
    print(f"login1: camera id {camera_id!r}")

    s.sendall(pack(CMD_LOGIN2_REQ, login2_payload(a.user.encode(), a.pwd.encode(), bf)))
    cmd, p = rd.packet()
    if cmd != CMD_LOGIN2_RESP or len(p) != 5:
        sys.exit(f"unexpected reply to login2: cmd={cmd:#x} {p.hex()}")
    result, extra = p[0], struct.unpack_from("<I", p, 1)[0]
    print(f"login2: result={result} extra={extra}")
    if result != 0:
        sys.exit("login rejected")

    s.sendall(pack(CMD_P2P_TYPE_NOTIFY, b"\0")
              + pack(CMD_PLAY_VIDEO, struct.pack("<I", a.stream))
              + pack(CMD_SET_VIDEO_PERFORMANCE, bytes([3, 1])))

    frames = 0
    nbytes = 0
    frag = None  # (header, bytearray)
    t_end = time.time() + a.seconds
    last_sent = time.time()
    with open(a.out, "wb") as out:
        while time.time() < t_end:
            if time.time() - last_sent >= KEEPALIVE_INTERVAL:
                s.sendall(pack(CMD_KEEPALIVE))
                last_sent = time.time()
            cmd, p = rd.packet()
            frame = None
            if cmd == CMD_VIDEO:
                h = parse_frame_header(p[:17], False)
                frame = (h, p[17:17 + h["len"]])
            elif cmd == CMD_VIDEO3:
                h = parse_frame_header(p[:23], True)
                frame = (h, p[23:23 + h["len"]])
            elif cmd in (CMD_VIDEO2_FRAGMENT, CMD_VIDEO4_FRAGMENT):
                hl = 17 if cmd == CMD_VIDEO2_FRAGMENT else 23
                flags = p[0]
                body = p[1:]
                if flags & 1:
                    h = parse_frame_header(body[:hl], cmd == CMD_VIDEO4_FRAGMENT)
                    frag = (h, bytearray())
                    body = body[hl:]
                if frag and flags & 2:
                    frag[1].extend(body)
                if frag and len(frag[1]) >= frag[0]["len"]:
                    frame = (frag[0], bytes(frag[1]))
                    frag = None
            elif a.verbose:
                print(f"cmd {cmd:#04x} len {len(p)}: {p[:48].hex()}")
            if frame:
                h, data = frame
                frames += 1
                nbytes += len(data)
                out.write(data)
                if a.verbose or frames <= 5:
                    print(f"frame {frames} cmd={cmd:#x} {h} head={data[:8].hex()}")
    print(f"{frames} frames, {nbytes} bytes -> {a.out}")
    s.close()


if __name__ == "__main__":
    main()
