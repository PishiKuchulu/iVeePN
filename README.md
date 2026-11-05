# iVeePN — Android client for the VeePN free network

**English** | [فارسی](#فارسی)

iVeePN is an unofficial Android client for the free VeePN network — the same
network the official free VeePN browser extension uses. It is a guest-mode
client: **there is no account and no sign-in**. The app requests an anonymous
guest token from VeePN's API, fetches the free location/server list, and
tunnels this device's traffic through VeePN's HTTPS proxy servers.

> **Honesty box — read before use**
>
> - Your traffic exits onto the internet **through VeePN's free network**.
>   VeePN operates those servers; this app cannot change what the operator
>   of an exit server can see.
> - To obtain a guest token, the app sends VeePN a **persistent per-install
>   UUID** and the **device time zone** (plus an app version and a browser
>   user-agent string), exactly like the official extension does. This is
>   not an anonymity tool.
> - The upstream protocol is **TCP-only** (HTTPS CONNECT). UDP traffic other
>   than DNS handled inside the tunnel is not carried.
> - Free locations only. Premium locations are listed for completeness in
>   the app but cannot be used in guest mode.
> - Using the free network may conflict with VeePN's terms of service; the
>   desktop reference project carries the same warning. Use at your own
>   discretion.

## Features

- System-wide VPN via Android `VpnService` (hev-socks5-tunnel), with
  fail-closed IPv6: if the IPv6 default route cannot be claimed, the tunnel
  is refused — and if TUN fails entirely, the app falls back **loudly** to
  proxy-only mode (configurable in Settings).
- Local SOCKS5 proxy (127.0.0.1:1080 by default) and optional local HTTP
  proxy, usable on their own in proxy-only mode.
- Locations screen: free/all tabs, search, optimal-location marker,
  selection persisted across launches.
- Server pool with circuit breaker, rotation and throttled credential
  refresh — a port of the desktop client's tunnel factory.
- Split tunneling (per-app exclusion), custom MTU, DNS resolver choice
  (DoH) for the local proxy, in-app log viewer, honest in-app updater
  (failures show the HTTP status, never a false "up to date").
- "Test connection chain" in Settings runs the full viability chain
  (guest token → locations → server list → exit IP) with per-step results.
- Current UI is a functional placeholder; the original iVeePN design
  arrives in a later phase.

## Privacy & security notes

- TLS is verified against the Android system trust store everywhere.
  There is **no** insecure/skip-verify mode anywhere in the app.
- The guest token, install UUID and cached server lists live in
  EncryptedSharedPreferences (fail-closed: if encryption is unavailable,
  storage is session-only in memory, never plaintext on disk).
- Server passwords and tokens are never written to the app log.
- LAN and local-network destinations are deliberately **blocked**
  through the tunnel: the local proxies refuse private-range,
  link-local and loopback targets, and there is currently no option to
  allow LAN access while connected.
- The app excludes **itself** from its own VPN, so its own control
  traffic runs outside the tunnel by design. This covers update checks
  against GitHub, the reserve-domain fetches behind the API domain
  rotation, and the real-IP check shown while disconnected — those
  requests reach their services from your real IP address even while
  the VPN is connected.

## Origins & credits

- The Android technical skeleton (VPN service mechanics, local proxy
  servers, updater) originates from
  [PishiKuchulu/iFoxy](https://github.com/PishiKuchulu/iFoxy), itself based
  on [Vauth/FoxyVPN](https://github.com/Vauth/FoxyVPN). iVeePN's UI and
  identity are original to this project.
- The VeePN protocol implementation (guest token flow, domain rotation,
  CONNECT upstream, server pool behaviour) is a Kotlin port of the desktop
  reference [SirBNL/VPeeN](https://github.com/SirBNL/VPeeN).
- The native tunnel is [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel),
  pinned to a fixed commit and verified at build time.

## Status

Version 1.0.5 — the protocol layer (P0/P1) is implemented and statically
verified; the first CI build and the live on-device chain test are the
next gates. See [CHANGELOG.md](CHANGELOG.md).

---

## فارسی

**iVeePN** یک کلاینت غیررسمی اندروید برای شبکه رایگان VeePN است — همان
شبکه‌ای که افزونه رایگان رسمی VeePN روی مرورگر استفاده می‌کند. این برنامه
در حالت مهمان کار می‌کند: **هیچ حساب کاربری و هیچ ورود (لاگین) ندارد**.
برنامه یک توکن مهمان ناشناس از API ‏VeePN می‌گیرد، فهرست لوکیشن‌ها و
سرورهای رایگان را دریافت می‌کند و ترافیک دستگاه را از طریق سرورهای پروکسی
HTTPS ‏VeePN تونل می‌کند.

> **شفافیت — قبل از استفاده بخوانید**
>
> - ترافیک شما **از طریق شبکه رایگان VeePN** وارد اینترنت می‌شود. سرورهای
>   خروجی دست VeePN است؛ این برنامه نمی‌تواند چیزی را که اپراتور سرور خروجی
>   می‌بیند تغییر دهد.
> - برای گرفتن توکن مهمان، برنامه یک **UUID ثابت برای هر نصب** و **منطقه
>   زمانی دستگاه** را (همراه نسخه برنامه و رشته User-Agent مرورگر) برای
>   VeePN می‌فرستد؛ دقیقاً مثل افزونه رسمی. این ابزار ناشناس‌سازی نیست.
> - پروتکل بالادستی **فقط TCP** است (HTTPS CONNECT). ترافیک UDP به‌جز DNS
>   داخل تونل منتقل نمی‌شود.
> - فقط لوکیشن‌های رایگان. لوکیشن‌های Premium در برنامه دیده می‌شوند ولی
>   در حالت مهمان قابل استفاده نیستند.
> - استفاده از شبکه رایگان ممکن است با شرایط استفاده VeePN تعارض داشته
>   باشد؛ پروژه مرجع دسکتاپ هم همین هشدار را دارد. مسئولیت استفاده با
>   خودتان است.

### امکانات

- VPN سراسری با `VpnService` اندروید (hev-socks5-tunnel) با IPv6
  fail-closed: اگر مسیر پیش‌فرض IPv6 قابل تصاحب نباشد، تونل ساخته
  نمی‌شود؛ و اگر TUN کامل شکست بخورد، برنامه **با اطلاع‌رسانی واضح** به
  حالت فقط-پروکسی می‌رود (قابل تنظیم در تنظیمات).
- پروکسی SOCKS5 محلی (پیش‌فرض 127.0.0.1:1080) و پروکسی HTTP محلی
  اختیاری، قابل استفاده مستقل در حالت فقط-پروکسی.
- صفحه لوکیشن‌ها: تب رایگان/همه، جستجو، نشان لوکیشن بهینه و ذخیره
  انتخاب بین اجراها.
- استخر سرور با قطع‌کن مدار (circuit breaker)، چرخش و نوسازی
  throttle‌شده اعتبارنامه‌ها — پورت‌شده از tunnel factory نسخه دسکتاپ.
- تونل‌بندی تقسیم‌شده (حذف برنامه‌های انتخابی)، MTU سفارشی، انتخاب DNS
  (DoH) برای پروکسی محلی، نمایش لاگ داخل برنامه و به‌روزرسانی صادقانه
  (خطاها با کد HTTP نمایش داده می‌شوند، نه «به‌روز است» دروغین).
- دکمه «تست زنجیره اتصال» در تنظیمات، کل زنجیره را زنده آزمایش می‌کند
  (توکن مهمان ← لوکیشن‌ها ← فهرست سرور ← IP خروجی).
- رابط کاربری فعلی یک پوسته عملکردی موقت است؛ طراحی اصلی iVeePN در فاز
  بعدی می‌آید.

### خاستگاه و اعتبارها

- اسکلت فنی اندروید (مکانیک سرویس VPN، سرورهای پروکسی محلی، به‌روزرسان)
  از [PishiKuchulu/iFoxy](https://github.com/PishiKuchulu/iFoxy) آمده که
  خودش بر پایه [Vauth/FoxyVPN](https://github.com/Vauth/FoxyVPN) است.
  رابط کاربری و هویت iVeePN اصیل و متعلق به همین پروژه است.
- پیاده‌سازی پروتکل VeePN (جریان توکن مهمان، چرخش دامنه، بالادستی
  CONNECT، رفتار استخر سرور) پورت کاتلینی مرجع دسکتاپ
  [SirBNL/VPeeN](https://github.com/SirBNL/VPeeN) است.
- تونل نیتیو [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  است که روی یک کامیت ثابت pin شده و هنگام بیلد راستی‌آزمایی می‌شود.

### وضعیت

نسخه 1.0.5 — لایه پروتکل (P0/P1) پیاده‌سازی و به‌صورت استاتیک
راستی‌آزمایی شده است؛ اولین بیلد CI و تست زنده زنجیره روی دستگاه،
گام‌های بعدی‌اند. ببینید: [CHANGELOG.md](CHANGELOG.md).
