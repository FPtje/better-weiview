#!/usr/bin/env python3
"""Laptop prototype of the Weiview ear cam "icap" live-video protocol.

Clean-room reimplementation from docs/icap-protocol.md (Python stdlib only).
Connects over TCP, logs in, starts the live stream and writes the raw video
elementary stream to a file. This camera sends MJPEG: play the file with
``ffplay -f mjpeg out.mjpeg``.

Usage: icap_client.py [--host 192.168.1.1] [--port 80] [--iface wlan0]
                      [--stream 0] [--seconds 20] [--out out.mjpeg] [-v]
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass

MAGIC = bytes.fromhex("05338b11")
DEFAULT_KEY = b"hello kitty and kgb/cia 2011 COPYRIGHT@REECAM 5460"
KEEPALIVE_INTERVAL = 10.0  # seconds; the vendor SDK waits 30 s

CMD_LOGIN1_REQ = 0x00
CMD_LOGIN1_RESP = 0x01
CMD_LOGIN2_REQ = 0x02
CMD_LOGIN2_RESP = 0x03
CMD_KEEPALIVE = 0x04
CMD_PLAY_VIDEO = 0x0E
CMD_VIDEO = 0x11
CMD_SET_VIDEO_PERFORMANCE = 0x1C
CMD_VIDEO2_FRAGMENT = 0x25
CMD_P2P_TYPE_NOTIFY = 0x2F
CMD_VIDEO3 = 0x34
CMD_VIDEO4_FRAGMENT = 0x35


# --- Blowfish variant -------------------------------------------------------

def _pi_hex_words(count: int) -> list[int]:
    """First `count` 32-bit words of the fractional hex digits of pi (Machin)."""
    guard_bits = 64
    prec = count * 32 + guard_bits

    def arctan_inv(x: int) -> int:
        # arctan(1/x) * 2^prec using the integer series
        total = term = (1 << prec) // x
        x2, n, sign = x * x, 1, 1
        while term:
            term //= x2
            n += 2
            sign = -sign
            total += sign * (term // n)
        return total

    pi = 16 * arctan_inv(5) - 4 * arctan_inv(239)
    frac = (pi - (3 << prec)) >> guard_bits
    return [(frac >> (32 * (count - 1 - i))) & 0xFFFFFFFF for i in range(count)]


def _sbox_init() -> list[list[int]]:
    """The 1024 pi words after the first 18, with the camera's five typos."""
    words = _pi_hex_words(18 + 1024)
    boxes = [words[18 + 256 * b:18 + 256 * (b + 1)] for b in range(4)]
    for box, idx, val in ((0, 227, 0x56DFA6AA), (0, 250, 0xFF340528),
                          (2, 25, 0x55FA3941), (2, 187, 0x915F958E),
                          (3, 23, 0x42D04DC6)):
        boxes[box][idx] = val
    return boxes


_SBOX_INIT = _sbox_init()


class CamBlowfish:
    """Blowfish with the camera's quirks (docs/icap-protocol.md, "Cipher").

    * The P-array starts as all zeros (not the pi digits) before the key is
      XORed in.
    * Five S-box entries differ from the standard pi-derived tables.
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

    def _f(self, x: int) -> int:
        s = self.S
        return ((((s[0][x >> 24] + s[1][(x >> 16) & 0xFF]) & 0xFFFFFFFF)
                 ^ s[2][(x >> 8) & 0xFF]) + s[3][x & 0xFF]) & 0xFFFFFFFF

    def _rounds(self, l: int, r: int, order: range, last: tuple[int, int]) -> tuple[int, int]:
        p = self.P
        for i in order:
            l ^= p[i]
            r ^= self._f(l)
            l, r = r, l
        return r ^ p[last[0]], l ^ p[last[1]]

    def _enc(self, l: int, r: int) -> tuple[int, int]:
        return self._rounds(l, r, range(16), (17, 16))

    def _dec(self, l: int, r: int) -> tuple[int, int]:
        return self._rounds(l, r, range(17, 1, -1), (0, 1))

    def _apply(self, data: bytes, fn: Callable[[int, int], tuple[int, int]]) -> bytes:
        if len(data) % 8:
            raise ValueError("Blowfish data must be a multiple of 8 bytes")
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

def packet(cmd: int, payload: bytes = b"") -> bytes:
    return MAGIC + struct.pack("<HI", cmd, len(payload)) + payload


def string_block_size(length: int) -> int:
    """String + NUL, rounded up to 8 bytes."""
    return (length + 8) & ~7


def string_block(s: bytes) -> bytes:
    """The string, a NUL, then zero filler up to string_block_size."""
    return s.ljust(string_block_size(len(s)), b"\0")


def encrypted_string(s: bytes, bf: CamBlowfish) -> bytes:
    """u8 len + 1, then the encrypted string block."""
    return bytes([len(s) + 1]) + bf.encrypt(string_block(s))


class Reader:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.buf = bytearray()

    def _fill(self, n: int) -> None:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("camera closed the connection")
            self.buf += chunk

    def packet(self) -> tuple[int, bytes]:
        """Reads the next packet, skipping any bytes before the magic."""
        self._fill(10)
        idx = self.buf.find(MAGIC)
        while idx != 0:
            del self.buf[:idx if idx > 0 else -3]
            self._fill(10)
            idx = self.buf.find(MAGIC)
        cmd, length = struct.unpack_from("<HI", self.buf, 4)
        self._fill(10 + length)
        payload = bytes(self.buf[10:10 + length])
        del self.buf[:10 + length]
        return cmd, payload


# --- Video frames -----------------------------------------------------------

@dataclass
class FrameHeader:
    codec: int
    resolution: int
    key_frame: bool
    tick: int
    accel: tuple[int, int, int] | None
    length: int

    @staticmethod
    def size(extended: bool) -> int:
        return 23 if extended else 17

    @classmethod
    def parse(cls, h: bytes, extended: bool) -> FrameHeader:
        codec, resolution, _, _, key, tick, _ = struct.unpack_from("<BBBBBII", h)
        accel = struct.unpack_from("<hhh", h, 13) if extended else None
        (length,) = struct.unpack_from("<I", h, cls.size(extended) - 4)
        return cls(codec, resolution, key != 0, tick, accel, length)


class FrameAssembler:
    """Turns video packets (whole or fragmented) into (header, data) frames."""

    def __init__(self) -> None:
        self.header: FrameHeader | None = None
        self.data = bytearray()

    def accept(self, cmd: int, p: bytes) -> tuple[FrameHeader, bytes] | None:
        if cmd in (CMD_VIDEO, CMD_VIDEO3):
            extended = cmd == CMD_VIDEO3
            size = FrameHeader.size(extended)
            h = FrameHeader.parse(p[:size], extended)
            return h, p[size:size + h.length]
        if cmd in (CMD_VIDEO2_FRAGMENT, CMD_VIDEO4_FRAGMENT):
            extended = cmd == CMD_VIDEO4_FRAGMENT
            flags, body = p[0], p[1:]
            if flags & 1:
                size = FrameHeader.size(extended)
                self.header = FrameHeader.parse(body[:size], extended)
                self.data = bytearray()
                body = body[size:]
            if self.header is None:
                return None
            if flags & 2:
                self.data += body
            if len(self.data) < self.header.length:
                return None
            frame = self.header, bytes(self.data)
            self.header = None
            return frame
        return None


# --- Session ----------------------------------------------------------------

def login(sock: socket.socket, rd: Reader, user: bytes, pwd: bytes) -> None:
    bf = CamBlowfish(DEFAULT_KEY)
    sock.sendall(packet(CMD_LOGIN1_REQ))
    cmd, p = rd.packet()
    if cmd != CMD_LOGIN1_RESP:
        sys.exit(f"unexpected reply to login1: cmd={cmd:#x} {p.hex()}")
    n = p[0]
    block = bf.decrypt(p[1:1 + string_block_size(n - 1)])
    camera_id = block[:n].split(b"\0")[0].decode(errors="replace")
    print(f"login1: camera id {camera_id!r}")

    sock.sendall(packet(CMD_LOGIN2_REQ, encrypted_string(user, bf) + encrypted_string(pwd, bf)))
    cmd, p = rd.packet()
    if cmd != CMD_LOGIN2_RESP or len(p) != 5:
        sys.exit(f"unexpected reply to login2: cmd={cmd:#x} {p.hex()}")
    result, extra = struct.unpack("<BI", p)
    print(f"login2: result={result} extra={extra}")
    if result != 0:
        sys.exit("login rejected")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default="192.168.1.1")
    ap.add_argument("--port", type=int, default=80)
    ap.add_argument("--iface", default="wlan0",
                    help="bind to this interface ('' for none); "
                         "needed when another interface is on 192.168.1.0/24")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pwd", default="")
    ap.add_argument("--stream", type=int, default=0)
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--out", default="out.mjpeg")
    ap.add_argument("-v", "--verbose", action="store_true")
    a = ap.parse_args()

    s = socket.socket()
    if a.iface:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BINDTODEVICE, a.iface.encode())
    s.settimeout(10)
    s.connect((a.host, a.port))
    rd = Reader(s)
    login(s, rd, a.user.encode(), a.pwd.encode())

    s.sendall(packet(CMD_P2P_TYPE_NOTIFY, b"\0")
              + packet(CMD_PLAY_VIDEO, struct.pack("<I", a.stream))
              + packet(CMD_SET_VIDEO_PERFORMANCE, bytes([3, 1])))

    frames = 0
    nbytes = 0
    assembler = FrameAssembler()
    t_end = time.time() + a.seconds
    last_sent = time.time()
    with open(a.out, "wb") as out:
        while time.time() < t_end:
            if time.time() - last_sent >= KEEPALIVE_INTERVAL:
                s.sendall(packet(CMD_KEEPALIVE))
                last_sent = time.time()
            cmd, p = rd.packet()
            frame = assembler.accept(cmd, p)
            if frame is None:
                if a.verbose and cmd not in (CMD_VIDEO2_FRAGMENT, CMD_VIDEO4_FRAGMENT):
                    print(f"cmd {cmd:#04x} len {len(p)}: {p[:48].hex()}")
                continue
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
