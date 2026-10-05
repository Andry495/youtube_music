# Third-party notices

## ByeDPI
- Source: https://github.com/hufrea/byedpi
- License: MIT
- Used as a local SOCKS5 DPI-bypass proxy (native library), including TUN path via hev.

## hev-socks5-tunnel
- Source: https://github.com/heiher/hev-socks5-tunnel
- License: MIT
- Bridges Android VpnService TUN traffic to the local ByeDPI SOCKS5 proxy (app-only).

JNI glue for ByeDPI command-line startup is adapted from patterns used by
community Android ports of ByeDPI; YouTube Voice ships its own local TUN/SOCKS UI.
