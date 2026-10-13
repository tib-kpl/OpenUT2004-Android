"""Convertit une capture de l'ecran virtuel Xvfb (XWD 32 bits) en PNG. Usage : xwd2png.py in.xwd out.png"""
import struct
import sys

from PIL import Image

data = open(sys.argv[1], "rb").read()
h = struct.unpack(">25I", data[:100])
header_size, width, height, byte_order, bytes_per_line, ncolors = h[0], h[4], h[5], h[7], h[12], h[19]
offset = header_size + ncolors * 12
raw = data[offset:offset + bytes_per_line * height]
mode = "BGRX" if byte_order == 0 else "XRGB"
Image.frombuffer("RGBX", (width, height), raw, "raw", mode, bytes_per_line, 1).convert("RGB").save(sys.argv[2])
