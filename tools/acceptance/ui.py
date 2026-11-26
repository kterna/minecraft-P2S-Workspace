#!/usr/bin/env python3
"""Drive the client in the private Xvfb display created by client.py. Coordinates are game-window pixels."""
import argparse
import ctypes as c
import ctypes.util
import json
import os
from pathlib import Path
import struct
import sys
import time
import zlib


Ptr, Int, UInt, ULong = c.c_void_p, c.c_int, c.c_uint, c.c_ulong
class Image(c.Structure):
    _fields_ = ([(k, Int) for k in ("width", "height", "xoffset", "format")] + [("data", Ptr)]
                + [(k, Int) for k in ("byte_order","bitmap_unit","bitmap_bit_order","bitmap_pad","depth","bytes_per_line","bits_per_pixel")]
                + [(k, ULong) for k in ("red_mask", "green_mask", "blue_mask")])


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.argtypes, fn.restype = args, result
    return fn


class Window:
    def __init__(self, run):
        metadata = json.loads((run / "display.json").read_text())
        os.environ.update(DISPLAY=metadata["display"], XAUTHORITY=metadata["authority"])
        self.x = c.CDLL(ctypes.util.find_library("X11"))
        self.xt = c.CDLL(ctypes.util.find_library("Xtst"))
        self.display = bind(self.x, "XOpenDisplay", Ptr, c.c_char_p)(None)
        if not self.display:
            raise RuntimeError("Cannot connect to the acceptance X display")
        self.root = bind(self.x, "XDefaultRootWindow", ULong, Ptr)(self.display)
        children = c.POINTER(ULong)()
        root, parent, count = ULong(), ULong(), UInt()
        bind(self.x, "XQueryTree", Int, Ptr, ULong, c.POINTER(ULong), c.POINTER(ULong),
             c.POINTER(c.POINTER(ULong)), c.POINTER(UInt))(
                 self.display, self.root, c.byref(root), c.byref(parent), c.byref(children), c.byref(count))
        try:
            for i in reversed(range(count.value)):
                xid = children[i]
                name = c.c_char_p()
                if not bind(self.x, "XFetchName", Int, Ptr, ULong, c.POINTER(c.c_char_p))(self.display, xid, c.byref(name)):
                    continue
                title = name.value.decode(errors="replace") if name.value else ""
                self.free(name)
                if "Minecraft" in title:
                    self.window, self.title = xid, title
                    break
            else:
                raise RuntimeError("The Minecraft window has not been mapped yet")
        finally:
            self.free(children)
        x, y, border, depth = Int(), Int(), UInt(), UInt()
        w, h = UInt(), UInt()
        bind(self.x, "XGetGeometry", Int, Ptr, ULong, c.POINTER(ULong), c.POINTER(Int), c.POINTER(Int),
             c.POINTER(UInt), c.POINTER(UInt), c.POINTER(UInt), c.POINTER(UInt))(
             self.display, self.window, c.byref(root), c.byref(x), c.byref(y), c.byref(w), c.byref(h), c.byref(border), c.byref(depth))
        bind(self.x, "XTranslateCoordinates", Int, Ptr, ULong, ULong, Int, Int, c.POINTER(Int), c.POINTER(Int), c.POINTER(ULong))(
             self.display, self.window, self.root, 0, 0, c.byref(x), c.byref(y), c.byref(parent))
        self.width, self.height, self.left, self.top = w.value, h.value, x.value, y.value

    def free(self, pointer):
        if pointer:
            bind(self.x, "XFree", Int, Ptr)(c.cast(pointer, Ptr))

    def flush(self):
        bind(self.x, "XSync", Int, Ptr, Int)(self.display, 0)

    def keycode(self, sym):
        if len(sym) == 1 and ' ' <= sym <= '~':
            keysym = ord(sym)
        else:
            keysym = bind(self.x, "XStringToKeysym", ULong, c.c_char_p)(sym.encode())
        code = bind(self.x, "XKeysymToKeycode", c.c_ubyte, Ptr, ULong)(self.display, keysym)
        if not code:
            raise ValueError(f"Key is unavailable in this X keyboard layout: {sym!r}")
        return code, keysym

    def key(self, code, down):
        bind(self.xt, "XTestFakeKeyEvent", Int, Ptr, UInt, Int, ULong)(self.display, code, int(down), 0)
        self.flush()

    def chord(self, keys):
        held = []
        try:
            for symbol in keys:
                code, _ = self.keycode(symbol)
                self.key(code, True)
                held.append(code)
            time.sleep(.09)
        finally:
            for code in reversed(held):
                self.key(code, False)
        time.sleep(.09)

    def type_text(self, text):
        for char in text:
            if not ' ' <= char <= '~':
                raise ValueError("type accepts printable ASCII; use the companion for Unicode text")
            code, sym = self.keycode(char)
            unshifted = bind(self.x, "XkbKeycodeToKeysym", ULong, Ptr, c.c_ubyte, Int, Int)(self.display, code, 0, 0)
            self.chord(["Shift_L", char] if unshifted != sym else [char])

    def click(self, x, y, button):
        if not (0 <= x < self.width and 0 <= y < self.height and 1 <= button <= 5):
            raise ValueError("Click outside the game window or invalid mouse button")
        bind(self.xt, "XTestFakeMotionEvent", Int, Ptr, Int, Int, Int, ULong)(self.display, -1, self.left+x, self.top+y, 0)
        self.flush()
        time.sleep(.1)
        press = bind(self.xt, "XTestFakeButtonEvent", Int, Ptr, UInt, Int, ULong)
        press(self.display, button, 1, 0)
        self.flush()
        time.sleep(.1)
        press(self.display, button, 0, 0)
        self.flush()

    def screenshot(self, path):
        fn = bind(self.x, "XGetImage", c.POINTER(Image), Ptr, ULong, Int, Int, UInt, UInt, ULong, Int)
        image = fn(self.display, self.root, self.left, self.top, self.width, self.height, 0xffffffff, 2)
        if not image:
            raise RuntimeError("XGetImage failed")
        try:
            img = image.contents
            if (img.bits_per_pixel, img.byte_order, img.red_mask, img.green_mask, img.blue_mask) != (32, 0, 0xff0000, 0xff00, 0xff):
                raise RuntimeError("Expected Xvfb 24-bit TrueColor BGRX framebuffer")
            buf = c.string_at(img.data, img.bytes_per_line * self.height)
            scan = []
            for y in range(self.height):
                row = buf[y*img.bytes_per_line:y*img.bytes_per_line+self.width*4]
                rgb = bytearray(self.width * 3)
                rgb[0::3], rgb[1::3], rgb[2::3] = row[2::4], row[1::4], row[0::4]
                scan.append(b"\0" + rgb)
        finally:
            bind(self.x, "XDestroyImage", Int, c.POINTER(Image))(image)
        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag+data) & 0xffffffff)
        header = struct.pack(">2I5B", self.width, self.height, 8, 2, 0, 0, 0)
        path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(scan), 5)) + chunk(b"IEND", b""))

    def close(self):
        bind(self.x, "XCloseDisplay", Int, Ptr)(self.display)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("info")
    sub.add_parser("screenshot").add_argument("path", type=Path)
    click = sub.add_parser("click")
    click.add_argument("x", type=int); click.add_argument("y", type=int); click.add_argument("button", nargs="?", type=int, default=1)
    sub.add_parser("key").add_argument("keys", nargs="+", help="X key names, pressed in sequence")
    sub.add_parser("chord").add_argument("keys", nargs="+", help="X key names, held simultaneously")
    sub.add_parser("type").add_argument("text")
    args = parser.parse_args()
    window = Window(args.run_dir)
    try:
        if args.command == "info":
            print(json.dumps({"title":window.title, "width":window.width, "height":window.height,"left":window.left,"top":window.top}))
        elif args.command == "screenshot":
            window.screenshot(args.path)
        elif args.command == "click":
            window.click(args.x, args.y, args.button)
        elif args.command == "type":
            window.type_text(args.text)
        elif args.command == "chord":
            window.chord(args.keys)
        else:
            for key in args.keys:
                window.chord([key])
    finally:
        window.close()


if __name__ == "__main__":
    main()
