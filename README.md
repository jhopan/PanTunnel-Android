<div align="center">

# PanTunnel Android

**VPN ringan berbasis sing-box — VLESS WebSocket TLS**

Kotlin rewrite dari [JPS Tunnel](https://github.com/jhopan/JPS-Tunnel) · fokus Android

</div>

---

## Tentang

PanTunnel adalah klien VPN Android yang berjalan di atas [sing-box](https://github.com/SagerNet/sing-box)
dengan TUN stack gVisor. Hanya satu protokol yang didukung — **VLESS + WebSocket + TLS** — supaya
tidak ada jalur yang setengah jalan.

Repo ini adalah **rewrite penuh ke Kotlin** dari aplikasi Android JPS Tunnel (Java + XML).
Desktop/Go tidak termasuk di sini.

| | |
|---|---|
| **Bahasa** | 100% Kotlin (coroutines + Flow + ViewBinding) |
| **minSdk** | 24 (Android 7) |
| **targetSdk** | 35 |
| **ABI** | arm64-v8a, armeabi-v7a |
| **Core** | sing-box via `libbox.aar` |

---

## Fitur

### Koneksi
- VLESS + WebSocket + TLS · SNI, Host, Path dihormati persis dari URI
- DNS Cloudflare 1.1.1.1 (primary) + Google 8.8.8.8 (backup) lewat tunnel
- TUN gVisor — stabil lintas vendor (Qualcomm, MediaTek, Exynos)
- Status jujur: **Connecting → Checking → Connected**, `Connected` hanya setelah HTTP 204 nyata lewat tunnel
- Auto-reconnect maksimal 3x, tidak ada loop boros baterai
- Tetap hidup saat app di-swipe dari Recents (`onTaskRemoved`)

### Profil
- Multi-profil (maks 10) — import clipboard `vless://`, deep link, atau file `.pantunnel`
- Tap kartu untuk aktifkan, tombol Edit & Hapus per kartu
- Profil aktif ditandai bar hijau + nama cyan

### Lisensi Offline (HWID)
- Export config terkunci (`.pantunnel`) — AES-256-GCM berbasis HWID perangkat
- HWID salah / kedaluwarsa → ditolak otomatis
- Import link `vless://` milik sendiri → lisensi dilepas otomatis
- Tidak ada server, tidak ada remote revocation

### Efisiensi
- Log level `warn` di produksi — tidak ada file log menumpuk
- Traffic meter per sesi, reset tiap connect
- Tanpa wake lock

---

## Arsitektur

```
vless:// URI
    ↓  VlessParser.kt            (pure Kotlin, unit-tested)
VlessConfig
    ↓  SingboxConfig.kt          (pure Kotlin, unit-tested)
JSON config sing-box
    ↓  PanVpnService.kt          (VpnService + coroutines)
TUN gVisor ← semua traffic sistem
    ↓  VLESS WS TLS → server
ProbeClient → 204 lewat 127.0.0.1:10808  → status "Connected"
```

### Struktur paket

| Paket | Isi |
|---|---|
| `core/` | Logika murni Kotlin tanpa Android API — parser, config builder, codec lisensi, model profil. Semua di-unit-test. |
| `data/` | Persistensi `SharedPreferences`: profil, lisensi, HWID, pengaturan. |
| `service/` | `PanVpnService` (foreground service + libbox), `ProbeClient`, `TunnelStatusStore`, `TunnelBus`. |
| `ui` (root) | `SplashActivity`, `MainActivity`, `ProfileEditActivity`, `ProfileAdapter`. |

**Prinsip:** activity tidak pernah mengarang status. Ia merender event dari `TunnelBus`, dan jatuh
ke snapshot `TunnelStatusStore` saat dibuka ketika service sudah berjalan.

---

## Build

Syarat: **JDK 17**, **Android SDK 35**.

```bash
./gradlew assembleDebug      # → app/build/outputs/apk/debug/
./gradlew assembleRelease    # → app/build/outputs/apk/release/
./gradlew testDebugUnitTest  # 29 unit test
```

### Signing release

Buat `keystore.properties` di root (tidak di-commit):

```properties
storeFile=pantunnel.jks
storePassword=...
keyAlias=pantunnel
keyPassword=...
```

Tanpa file itu, build release memakai debug signing config supaya `assembleRelease` tetap jalan
untuk pengujian lokal.

### Core sing-box

`app/libs/libbox.aar` sudah disertakan (sing-box v1.11.x, tag build
`with_gvisor,with_clash_api`). Untuk membangun ulang lihat
[`build_libbox.sh`](https://github.com/jhopan/JPS-Tunnel/blob/main/build_libbox.sh) di repo JPS Tunnel.

---

## Test

29 unit test di `app/src/test/` mencakup:

- `VlessParserTest` — parsing, default, round-trip export, penolakan gRPC/OneRing, varian host:port
- `SingboxConfigTest` — bentuk JSON outbound/TUN/route/DNS
- `LicenseCodecTest` — round-trip, penolakan HWID lain (`AEADBadTagException`), expiry
- `ProfileBookTest` — batas profil, pemilihan suksesor setelah hapus, HWID

```bash
./gradlew testDebugUnitTest
```

---

## Catatan TUN stack

gVisor dipilih karena stabil lintas vendor. Stack `system` lebih ringan tetapi pernah tidak stabil
di beberapa chipset MediaTek. Untuk menguji: ubah `SingboxConfig.STACK`, uji minimal di 2 perangkat
berbeda sebelum dipakai produksi.

---

## Lisensi

MIT License
