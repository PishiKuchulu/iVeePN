# Third-party notices

iVeePN is built with, ported from, or derived from the following
third-party components. Their respective licenses apply to those
components.

## hev-socks5-tunnel

- TUN engine, fetched at build time at a pinned commit and verified
  before use.
- License: MIT
- Copyright (c) Heiher / the hev-socks5-tunnel contributors
- Upstream: https://github.com/heiher/hev-socks5-tunnel

## VPeeN (protocol reference)

- The VeePN protocol implementation (guest token flow, domain rotation,
  CONNECT upstream, server pool behaviour) was ported to Kotlin with
  reference to the VPeeN desktop project by SirBNL.
- License: MIT
- Upstream: https://github.com/SirBNL/VPeeN

## Vazirmatn font

- UI typeface (Regular, Medium, Bold), fetched at build time at a
  pinned version with SHA-256 verification.
- License: SIL Open Font License 1.1
- Copyright the Vazirmatn Project Authors
- Upstream: https://github.com/rastikerdar/vazirmatn

## iFoxy (skeleton origin)

- The Android VPN/proxy/updater skeleton (VPN service mechanics, local
  proxy servers, updater) was originally derived from the iFoxy project.
  iVeePN's UI and identity are original to this project.
- Upstream: https://github.com/PishiKuchulu/iFoxy
