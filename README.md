# 🌐 iVeePN

**Unofficial Android client for the VeePN free network — No account, no sign-in, just connect.**  
**کلاینت غیررسمی اندروید برای شبکه‌ی رایگان VeePN — بدون اکانت، بدون لاگین، فقط وصل شو!**

---

## 🇬🇧 English

**iVeePN** is an unofficial Android client for the free VeePN network — the same network the official free VeePN browser extension uses. It is a guest-mode client: **there is no account and no sign-in**. The app requests a guest token from VeePN's API, fetches the free location/server list, and tunnels this device's traffic through VeePN's HTTPS proxy servers.

### ⚠️ Honesty Box — Read Before Use

* Your traffic exits onto the internet **through VeePN's free network**. VeePN operates those servers; this app cannot change what the operator of an exit server can see.
* To obtain a guest token, the app sends VeePN a **persistent per-install UUID** and the **device time zone** (plus an app version and a browser user-agent string), exactly like the official extension does. This is **not an anonymity tool**.
* The upstream protocol is **TCP-only** (HTTPS CONNECT). UDP traffic other than DNS handled inside the tunnel is not carried.
* Free locations only. Premium locations are listed for completeness in the app but cannot be used in guest mode.
* The app excludes **itself** from its own VPN, so its own control traffic (update checks, API domain fetches, the real-IP check shown while disconnected) runs outside the tunnel and reaches those services from your real IP address, even while the VPN is connected.
* Using the free network may conflict with VeePN's terms of service. Use at your own discretion.

### ✨ Key Features

* 🌍 **System-wide VPN:** Uses Android `VpnService` (hev-socks5-tunnel) with fail-closed IPv6 protection.
* 🔒 **Fail-safe:** If the IPv6 default route cannot be claimed, the tunnel is refused. If TUN fails entirely, the connection fails by default; an optional fallback to proxy-only mode can be enabled in Settings, and when it triggers it is always surfaced loudly in the UI — never silent.
* 🧦 **Local Proxies:** SOCKS5 proxy (127.0.0.1:1080 by default) and optional local HTTP proxy, usable on their own in proxy-only mode.
* 📍 **Locations Screen:** Free/all tabs, search functionality, optimal-location marker, and selection persisted across launches.
* 📶 **Pre-connect Ping:** When the locations list loads, every free location is measured before you connect (TCP latency), with a quality indicator per row, a "Fastest" label, fastest-first sorting, and a manual re-test button.
* 🔄 **Smart Server Pool:** Circuit breaker, rotation, and throttled credential refresh — ported from the desktop client's tunnel factory.
* 🎯 **Split Tunneling:** Three per-app modes — all apps through the tunnel, all except selected apps, or only selected apps through the tunnel.
* ⚙️ **Advanced Options:** Custom MTU, DNS resolver choice (DoH) for the local proxy, in-app log viewer.
* 🧅 **Transport Options:** Optional upstream SOCKS5 proxy (with sign-in) that connections to VeePN servers transit first, encrypted DoH resolution for the VeePN API domains, and an experimental TLS handshake fragmentation toggle.
* 🧪 **Connection Test:** "Test connection chain" in Settings runs the full viability chain (guest token → locations → server list → exit IP) with per-step results.
* 📥 **Honest Updater:** In-app updater shows real HTTP status on failures, never a false "up to date" message.

### 🔐 Privacy & Security Notes

* TLS is verified against the Android system trust store everywhere. There is **no** insecure/skip-verify mode anywhere in the app.
* The guest token, install UUID and cached server lists live in EncryptedSharedPreferences (fail-closed: if encryption is unavailable, storage is session-only in memory, never plaintext on disk).
* Server credentials and tokens are never written to the app log.
* LAN and local-network destinations are deliberately **blocked** through the tunnel: the local proxies refuse private-range, link-local and loopback targets, and there is currently no option to allow LAN access while connected.

### 📲 Installation

1. Go to the [Releases](https://github.com/PishiKuchulu/iVeePN/releases) page.
2. Download the latest `iVeePN-<version>.apk`.
3. Install it (Android 8.0+, API 26).
4. Open the app, select a free location, and connect!

### 🛠️ Building from Source

Prerequisites: JDK 17, Android SDK 35, NDK 26.1.10909125. Then:

```bash
bash gradlew testDebugUnitTest assembleDebug
```

(`bash gradlew` instead of `./gradlew`: the executable bit on `gradlew` is currently not preserved by this repository's push route — the wrapper jar itself is in the tree, so the `bash` form bootstraps identically.)

### 🙏 Origins & Credits

* The Android technical skeleton (VPN service mechanics, local proxy servers, updater) originates from [PishiKuchulu/iFoxy](https://github.com/PishiKuchulu/iFoxy), itself based on [Vauth/FoxyVPN](https://github.com/Vauth/FoxyVPN). iVeePN's UI and identity are original to this project.
* The VeePN protocol implementation (guest token flow, domain rotation, CONNECT upstream, server pool behaviour) is a Kotlin port of the desktop reference [SirBNL/VPeeN](https://github.com/SirBNL/VPeeN).
* The native tunnel is [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), pinned to a fixed commit and verified at build time.

---

## 🇮🇷 فارسی

**iVeePN** یک کلاینت غیررسمی اندروید برای شبکه‌ی رایگان VeePN است — همان شبکه‌ای که افزونه‌ی مرورگر رسمی VeePN از آن استفاده می‌کند. این یک کلاینت حالت مهمان است: **نیازی به اکانت یا لاگین نیست**. برنامه یک توکن مهمان از API وی‌پی‌ان درخواست می‌کند، لیست سرورهای رایگان را می‌گیرد و ترافیک دستگاه شما را از طریق سرورهای پروکسی HTTPS وی‌پی‌ان تونل می‌کند.

### ⚠️ جعبه‌ی صداقت — قبل از استفاده بخوانید

* ترافیک شما **از طریق شبکه‌ی رایگان VeePN** به اینترنت می‌رسد. VeePN آن سرورها را اداره می‌کند؛ این برنامه نمی‌تواند تغییر دهد که اپراتور سرور خروجی چه چیزی را می‌بیند.
* برای دریافت توکن مهمان، برنامه یک **UUID ثابت برای هر نصب** و **منطقه‌ی زمانی دستگاه** (به علاوه‌ی نسخه‌ی برنامه و User-Agent مرورگر) را به VeePN می‌فرستد، دقیقاً مانند افزونه‌ی رسمی. این **ابزار ناشناسی نیست**.
* پروتکل بالادستی **فقط TCP** است (HTTPS CONNECT). ترافیک UDP به جز DNS که داخل تونل مدیریت می‌شود، منتقل نمی‌شود.
* فقط لوکیشن‌های رایگان. لوکیشن‌های پریمیوم برای کامل بودن در برنامه لیست شده‌اند اما در حالت مهمان قابل استفاده نیستند.
* خود برنامه از VPN خودش مستثناست؛ پس ترافیک کنترلی خودش (بررسی به‌روزرسانی، دریافت دامنه‌های API و بررسی IP واقعی در حالت قطع) بیرون از تونل می‌رود و حتی هنگام اتصال VPN، با IP واقعی شما به آن سرویس‌ها می‌رسد.
* استفاده از شبکه‌ی رایگان ممکن است با شرایط خدمات VeePN در تضاد باشد. با مسئولیت خودتان استفاده کنید.

### ✨ ویژگی‌های کلیدی

* 🌍 **VPN سراسری سیستم:** از `VpnService` اندروید (hev-socks5-tunnel) با محافظت IPv6 fail-closed استفاده می‌کند.
* 🔒 **ایمن در برابر شکست:** اگر مسیر پیش‌فرض IPv6 قابل ادعا نباشد، تونل رد می‌شود. اگر TUN کاملاً شکست بخورد، اتصال به‌صورت پیش‌فرض ناموفق می‌شود؛ بازگشت به حالت فقط پروکسی یک گزینه‌ی اختیاری در تنظیمات است و اگر فعال باشد و رخ دهد، همیشه به‌صورت شفاف و با هشدار واضح در رابط کاربری اعلام می‌شود — هرگز بی‌صدا نیست.
* 🧦 **پروکسی‌های محلی:** پروکسی SOCKS5 (به صورت پیش‌فرض 127.0.0.1:1080) و پروکسی HTTP محلی اختیاری که به تنهایی در حالت فقط پروکسی قابل استفاده‌اند.
* 📍 **صفحه‌ی لوکیشن‌ها:** تب‌های رایگان/همه، قابلیت جست‌وجو، نشانگر لوکیشن بهینه و ماندگاری انتخاب در اجراهای بعدی.
* 📶 **پینگ قبل از اتصال:** هنگام بارگذاری فهرست لوکیشن‌ها، تأخیر همه‌ی لوکیشن‌های رایگان قبل از اتصال سنجیده می‌شود (تأخیر TCP)؛ با نشانگر کیفیت کنار هر ردیف، برچسب «سریع‌ترین»، مرتب‌سازی سریع‌ترین‌اول و دکمه‌ی سنجش دوباره.
* 🔄 **استخر سرور هوشمند:** Circuit breaker، چرخش و تازه‌سازی اعتبارنامه‌ی کنترل‌شده — پورت‌شده از tunnel factory کلاینت دسکتاپ.
* 🎯 **Split Tunneling:** سه حالت برای هر برنامه — همهٔ برنامه‌ها از تونل، همه به‌جز برنامه‌های انتخاب‌شده، یا فقط برنامه‌های انتخاب‌شده از تونل.
* ⚙️ **گزینه‌های پیشرفته:** MTU سفارشی، انتخاب DNS resolver (DoH) برای پروکسی محلی، نمایشگر لاگ داخلی.
* 🧅 **گزینه‌های انتقال:** پراکسی SOCKS5 بالادستی اختیاری (با امکان ورود) که اتصال به سرورهای VeePN نخست از آن می‌گذرد، پیدا کردن دامنه‌های API با DoH رمزنگاری‌شده، و کلید آزمایشی قطعه‌قطعه‌کردن دست‌دهی TLS.
* 🧪 **تست اتصال:** "Test connection chain" در تنظیمات، زنجیره‌ی کامل قابلیت (توکن مهمان → لوکیشن‌ها → لیست سرور → IP خروجی) را با نتایج هر مرحله اجرا می‌کند.
* 📥 **آپدیتر صادق:** آپدیتر داخلی در صورت شکست، وضعیت HTTP واقعی را نشان می‌دهد، نه پیام دروغین "به‌روز است".

### 🔐 نکات حریم خصوصی و امنیت

* TLS در همه‌جا با trust store سیستم اندروید راستی‌آزمایی می‌شود و هیچ حالت ناامن یا ردکردن راستی‌آزمایی در برنامه وجود ندارد.
* توکن مهمان، UUID نصب و لیست‌های سرور کش‌شده در EncryptedSharedPreferences نگه داشته می‌شوند (fail-closed: اگر رمزنگاری در دسترس نباشد، نگهداری فقط در حافظه‌ی همان اجرا انجام می‌شود، هرگز به‌صورت متن ساده روی دیسک نمی‌نشیند).
* اعتبارنامه‌ی سرورها و توکن‌ها هرگز در لاگ برنامه نوشته نمی‌شوند.
* مقصدهای LAN و شبکه‌ی محلی عمداً از داخل تونل **مسدود** هستند: پروکسی‌های محلی هدف‌های بازه‌ی خصوصی، link-local و loopback را رد می‌کنند و فعلاً گزینه‌ای برای اجازه‌ی دسترسی LAN هنگام اتصال وجود ندارد.

### 📲 نصب و راه‌اندازی

۱. به بخش [Releases](https://github.com/PishiKuchulu/iVeePN/releases) بروید.
۲. آخرین نسخه (`iVeePN-<version>.apk`) را دانلود کنید.
۳. نصب کنید (نیازمند اندروید ۸.۰ به بالا، API 26).
۴. برنامه را باز کنید، یک لوکیشن رایگان انتخاب کنید و وصل شوید!

### 🛠️ بیلد از سورس

پیش‌نیازها: JDK 17، ‏Android SDK 35 و NDK 26.1.10909125. بعد:

```bash
bash gradlew testDebugUnitTest assembleDebug
```

(دلیل استفاده از `bash gradlew` به‌جای `./gradlew` این است که بیت اجرایی gradlew فعلاً در مسیر پوش این ریپو حفظ نمی‌شود — خود فایل wrapper jar در درخت هست و فرم bash دقیقاً همان بوت‌استرپ را انجام می‌دهد.)

### 🙏 خاستگاه و اعتبارها

* اسکلت فنی اندروید (مکانیک سرویس VPN، سرورهای پروکسی محلی، به‌روزرسان) از [PishiKuchulu/iFoxy](https://github.com/PishiKuchulu/iFoxy) آمده که خودش بر پایه‌ی [Vauth/FoxyVPN](https://github.com/Vauth/FoxyVPN) است. رابط کاربری و هویت iVeePN اصیل و متعلق به همین پروژه است.
* پیاده‌سازی پروتکل VeePN (جریان توکن مهمان، چرخش دامنه، بالادستی CONNECT، رفتار استخر سرور) پورت کاتلینی مرجع دسکتاپ [SirBNL/VPeeN](https://github.com/SirBNL/VPeeN) است.
* تونل نیتیو [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) است که روی یک کامیت ثابت pin شده و هنگام بیلد راستی‌آزمایی می‌شود.

---

**Developed with ❤️ by Farzad Sholeh**
