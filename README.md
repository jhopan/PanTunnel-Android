# PanTunnel Android

**VPN ringan berbasis sing-box — VLESS + WebSocket + TLS**

Kotlin 100% (coroutines + Flow + ViewBinding) · `minSdk 24` · `targetSdk 35` · arm64-v8a + armeabi-v7a
Core: **sing-box v1.11.15** (`app/libs/libbox.aar`) · APK rilis ~8,4 MB (arm64)

---

## Fitur

| Area | Isi |
|---|---|
| Koneksi | VLESS + WS + TLS; SNI/Host/Path dihormati persis dari URI; TUN gVisor; DNS lewat tunnel (1.1.1.1 + 8.8.8.8) |
| Status | Jujur: `Idle → Connecting → Checking → Connected`. `Connected` **hanya** setelah HTTP 204 nyata lewat tunnel |
| Pemulihan | Auto-reconnect maks 3×, lalu `Failed` dengan alasan nyata (bukan loop) |
| Background | Foreground service `START_STICKY` + `onTaskRemoved`, tetap hidup saat app di-swipe dari Recents |
| Profil | Maks 10 profil; import clipboard, deep link `vless://`, atau file `.pantunnel` |
| Lisensi offline | Export config terkunci `.pantunnel` (AES-256-GCM, terikat HWID perangkat); tanpa server |
| Split tunnel | Per-app, dua mode: hanya app terpilih lewat VPN, atau app terpilih dilewatkan |
| Hemat | Tanpa wake lock; probe/ping di-skip saat layar mati; log ring buffer 8 baris (TTL 60 s) |
| Diagnosa | Status box + export log dengan UUID teredaksi; hint "VPN dimatikan sistem" saat heartbeat basi |

---

## Download

Rilis resmi: **[github.com/jhopan/PanTunnel-Android/releases](https://github.com/jhopan/PanTunnel-Android/releases)**

| File | Untuk |
|---|---|
| `PanTunnel-v<ver>-v8.apk` | HP 64-bit (hampir semua HP 2018+) — **pilih ini kalau ragu** |
| `PanTunnel-v<ver>-v7.apk` | HP lama 32-bit |
| `PanTunnel-v<ver>-universal.apk` | Keduanya dalam satu APK (lebih besar) |
| `SHA256SUMS.txt` | Checksum |

Verifikasi sebelum install:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
apksigner verify --print-certs PanTunnel-v<ver>-v8.apk | grep SHA-256
# sertifikat resmi: aa60d4dd41207392e6372c9ddc9d4a41adff924b9937ed21291505e0c2e7ec37
```

Kalau sertifikatnya beda, **jangan** dipasang: APK tidak akan bisa meng-update instalasi lama.

### Cara pakai

1. Install APK (izinkan "install dari sumber tidak dikenal").
2. Salin link `vless://...` dari penjual/server, atau buka file `.pantunnel`.
3. App terbuka dengan profil terisi → **SIMPAN**.
4. Tap kartu profil untuk mengaktifkan (ditandai bar hijau).
5. Tap **CONNECT** → tunggu `Connected` + baris `HTTP ping 204` di status box.

Android 12+: izinkan notifikasi agar status VPN terlihat di shade. Saat app dipasang pertama kali,
app menawarkan "Mode 24/7" — atur penghemat daya ke **Tidak dibatasi** supaya VPN tidak dimatikan sistem.

---

## Split tunnel

Menu ⋮ → **Split tunnel** → aktifkan → pilih mode:

| Mode | Arti |
|---|---|
| "Hanya app terpilih yang LEWAT VPN" | allowlist — hanya app dipilih yang masuk tunnel |
| "App terpilih DILEWATKAN" | blocklist — app dipilih keluar langsung, sisanya lewat VPN |

Package PanTunnel sendiri **selalu** di luar tunnel di kedua mode: dial core ke server harus keluar
langsung, kalau ikut masuk tunnel dial-nya berbalik ke proxy dan timeout (`dial tcp ... i/o timeout`).
Probe tetap jalan karena memakai loopback `127.0.0.1:10808` yang tidak pernah lewat TUN.

---

## Build aplikasi sendiri

Prasyarat: **JDK 17**, **Android SDK 35**, Gradle **8.9** (wrapper sudah disertakan).

```bash
./gradlew :app:testDebugUnitTest          # 134 unit test, JVM saja
./gradlew :app:assembleDebug              # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease            # minify + shrinkResources
```

Windows (Git Bash di mesin ini tidak bisa `./gradlew`):

```bash
"C:/Users/ACER/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat" \
  :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Hasil: 3 APK per varian (`arm64-v8a`, `armeabi-v7a`, universal).

### Signing rilis

Buat `keystore.properties` di root (tidak di-commit):

```properties
storeFile=pantunnel-release.jks
storePassword=...
keyAlias=pantunnel
keyPassword=...
```

Tanpa file itu `assembleRelease` tetap jalan tapi memakai **debug key** — APK hasilnya tidak bisa
meng-update instalasi pembeli. Jangan commit `*.jks`; simpan backup di luar repo.

---

## Build core sing-box sendiri

`app/libs/libbox.aar` adalah hasil `gomobile bind` dari source sing-box. AAR tidak disediakan
terpisah oleh upstream; kalau perlu versi lain, build sendiri:

### 1. Toolchain (versi yang terbukti)

| Kebutuhan | Versi |
|---|---|
| Go | 1.26 (butuh `-checklinkname=0`) |
| Android NDK | `25.1.8937393` |
| gomobile | `github.com/sagernet/gomobile/cmd/gomobile@latest` |
| JDK | 17 atau 21 |

```bash
export PATH="$HOME/go/bin:/c/Program Files/Go/bin:$PATH"
export ANDROID_NDK_HOME="C:\\Users\\ACER\\AppData\\Local\\Android\\Sdk\\ndk\\25.1.8937393"
export ANDROID_HOME="C:\\Users\\ACER\\AppData\\Local\\Android\\Sdk"
```

### 2. Ambil source + patch bug upstream

Di sing-box 1.14.x ada 32 file `protocol/tailscale/**` dan `service/derp/*` bertag
`with_gvisor` padahal seharusnya `with_tailscale`; akibatnya mengaktifkan gVisor ikut menarik
seluruh library Tailscale (AAR 36 MB → 28,7 MB setelah dipatch).

```bash
git clone --depth 1 --branch v1.11.15 https://github.com/SagerNet/sing-box.git
cd sing-box
sed -i 's|^//go:build with_gvisor|//go:build with_tailscale|' \
  protocol/tailscale/*.go protocol/tailscale/tailssh/*.go service/derp/*.go   # hanya perlu di 1.14+
```

### 3. Build AAR

```bash
gomobile bind \
  -o "/path/libbox.aar" \
  -target=android/arm,android/arm64 \
  -androidapi=24 \
  -javapkg=io.github.sagernet.libbox \
  -tags="with_gvisor,with_clash_api,badlinkname,tfogo_checklinkname0" \
  -ldflags="-s -w -buildid= -checklinkname=0 \
    -X github.com/sagernet/sing-box/constant.Version=v1.11.15 \
    -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 \
    -extldflags=-Wl,-z,max-page-size=16384" \
  -trimpath -buildvcs=false \
  ./experimental/libbox
```

Catatan tag: `with_gvisor` **wajib** (TUN gVisor); `with_clash_api` wajib di 1.11 (tanpa ini
libbox panic); `badlinkname` + `tfogo_checklinkname0` + `-checklinkname=0` untuk Go 1.26.
**Jangan** tambah `with_quic` / `with_utls` — tidak dipakai dan hanya membengkak.

### 4. Verifikasi sebelum masuk Android

```bash
strings -a jni/arm64-v8a/libgojni.so | grep -oE "^v1\.[0-9]+\.[0-9]+$" | sort -u   # versi tertanam
strings -a jni/arm64-v8a/libgojni.so | grep -c "sagernet/tailscale"                # harus 0
ls -la libbox.aar                                                                  # 1.11.15 ≈ 16 MB
```

### 5. Tes config tanpa Android

```bash
curl -sSL -o sb.tar.gz https://github.com/SagerNet/sing-box/releases/download/v1.11.15/sing-box-1.11.15-linux-amd64.tar.gz
tar xzf sb.tar.gz && ./sing-box-1.11.15-linux-amd64/sing-box check -c config.json
# jalankan + uji trafik lewat proxy lokal; harus 204:
curl -x http://127.0.0.1:10808 -o /dev/null -w '%{http_code}\n' \
  https://connectivitycheck.gstatic.com/generate_204
```

### 6. Pasang & rollback

```bash
cp app/libs/libbox.aar backup/libbox-<versi-lama>.aar   # backup dulu
cp /path/libbox.aar app/libs/libbox.aar
./gradlew :app:assembleDebug
```

Rollback = kembalikan AAR lama. Setelah ganti versi, wajib sentuh `core/SingboxConfig.kt`,
`service/PanVpnService.kt`, `core/SingboxConfigTest.kt` (lihat tabel migrasi di bawah).

### Kalau naik ke sing-box 1.12+

| Lama (1.11) | Baru (1.12+) |
|---|---|
| `dns.servers[].address: "1.1.1.1"` | `{ "type": "udp", "server": "1.1.1.1" }` |
| `{ "tag": "x", "address": "local" }` | `{ "type": "local", "tag": "x" }` |
| `dns.rules[].outbound: "direct"` | `route.rules[].action` |
| `route.rules[].outbound: "dns-out"` | `{ "protocol": "dns", "action": "hijack-dns" }` |
| `outbounds[] { "type": "block" }` | `{ "action": "reject" }` di rule |
| TUN `inet4_address` | `address` |
| outbound `domain_strategy` | `route.default_domain_resolver` |
| `Libbox.newService()` + `BoxService` | `CommandServer(...)` → `start()` → `startOrReloadService()` |

1.14 juga menaikkan ukuran AAR ke ±28,7 MB (batas bawah) — itu sebabnya proyek ini masih di 1.11.15.

---

## Struktur repo

| Path | Isi |
|---|---|
| `core/` | Logika murni JVM, tanpa `android.*`: parser VLESS, builder config sing-box, lisensi/HWID, profil, kebijakan probe, klasifikasi error, split tunnel, cache janitor. Semua di-unit-test. |
| `data/` | `SharedPreferences` (file `pantunnel`): profil, lisensi, `install_id`, pengaturan. |
| `service/` | `PanVpnService` (VpnService + libbox), `ProbeClient`, `TunnelBus` (StateFlow), `TunnelStatusStore`. |
| root | Activities + `ProfileAdapter` (ViewBinding, tanpa DI framework). |
| `ui/` | `TrafficMeter`. |
| `app/src/test/` | 16 kelas unit test, JVM murni (tanpa Robolectric). |
| `app/libs/libbox.aar` | Core sing-box (Go via gomobile). |

Alur data:

```
vless:// → VlessParser → VlessConfig → SingboxConfig → JSON
        → Libbox.newService + Platform : PlatformInterface → TUN gVisor
        → VLESS WS TLS → ProbeClient (127.0.0.1:10808) → HTTP 204
        → TunnelBus / TunnelStatusStore → UI
```

---

## Testing & QA

```bash
./gradlew :app:testDebugUnitTest
```

- **JUnit 4** + `kotlin-test-junit`; tanpa Mockito/mockk, tanpa Robolectric.
- `org.json:json:20240303` wajib ada di `testImplementation` — stub `android.jar` mengembalikan
  default dan membuat `SingboxConfigTest` lulus palsu.
- 16 kelas / **134 `@Test`**: `SingboxConfigTest` 14, `TunnelDiagnosticsTest` 15,
  `NetworkErrorClassifierTest` 11, `ProbePolicyTest` 10, `StatusFreshnessTest` 10,
  `VpnAppRulesTest` 10, `ProfileBookTest` 9, `VlessParserTest` 9, `TransientErrorMonitorTest` 8,
  `LogBufferTest` 7, `SplitTunnelTest` 7, `CacheJanitorTest` 6, `LicenseCodecTest` 5,
  `PantunnelFileTest` 5, `CancellationDiagnosticsTest` 4, `DiagnosticsReportTest` 4.
- `PanVpnService` tidak punya unit test (butuh runtime Android) → setiap perubahan service wajib
  diuji di device. Minimal **2 chipset berbeda** (Qualcomm + MediaTek):

- [ ] Connect → `Connecting` → `Checking` → `Connected`, muncul `HTTP ping 204`
- [ ] Traffic meter naik; disconnect → `Terputus` dan `tun0` hilang (`ip addr show tun0`)
- [ ] Layar mati 10 menit → nyala → masih `Connected`
- [ ] Wi-Fi ↔ data seluler, mode pesawat 30 detik → auto-reconnect
- [ ] Swipe dari Recents → VPN tetap hidup; `onRevoke()` dari Settings tidak crash
- [ ] Export `.pantunnel` → ukuran file > 0 → import balik → lisensi terpasang
- [ ] Split tunnel allowlist **dan** blocklist sama-sama bisa `Connected`

Sebelum rilis: `assembleRelease` sukses, kelas `io.github.sagernet.libbox.libbox.*` masih ada di
APK (R8 tidak boleh menghapus JNI), dan `apksigner verify` menunjukkan sertifikat resmi.

---

## Prinsip stabilitas

Aturan paling keras: **status jujur**. Proses sing-box hidup ≠ tunnel bekerja, jadi `Connected`
hanya dipublish setelah `ProbeClient` menerima HTTP 204 lewat `127.0.0.1:10808` (3 percobaan).
Jangan pindahkan `publish(TunnelState.Connected, ...)` sebelum probe lolos.

Invariant lain yang tidak boleh dilanggar:

| Invariant | Alasan |
|---|---|
| `Libbox.setup()` sekali di `onCreate` | Dipanggil 2× → crash `CrashReport-.log: read-only file system` |
| `closeCore()` idempoten, job di-cancel sebelum start ulang | Coroutine yatim = status bohong / kebocoran |
| `CancellationException` di `connect()` di-rethrow | Teardown bukan kegagalan |
| `MAX_AUTO_RECONNECTS = 3` | Tanpa batas = loop boros baterai & data |
| `ProfileBook.MAX_PROFILES = 10` | `put()` mengembalikan `false` di batas itu |
| `clearConfig()` mempertahankan `install_id` | HWID berubah = lisensi user hilang permanen |
| Split tunnel: allowlist atau blocklist, tidak keduanya | Android menolak campuran; package sendiri selalu di luar tunnel |
| Stack TUN `gvisor` | `system` pernah tidak stabil di MediaTek |
| Layar mati → probe/ping skip | Hemat baterai; jangan diubah jadi probe terus |
| Traffic meter reset hanya saat transisi ke `Connected` | Reset tiap ping membaca 0 B/s |
| Paket sendiri di luar tunnel (kedua mode split) | Kalau di dalam, dial core ke server berbalik ke proxy → timeout |
| Jaringan hilang → status `Menunggu jaringan`, bukan `Connected` | Jaringan naik lagi → probe lolos → `Connected` otomatis (terukur: degrade 4 s, pulih 11 s) |
| Budget auto-reconnect tidak dibakar saat device offline | Reconnect tanpa jaringan hanya berakhir `Failed` yang harus di-tap user |

---

## Troubleshooting

| Gejala | Penyebab | Aksi |
|---|---|---|
| `Connected` tapi internet mati | Probe tidak lolos / core zombie | Jangan dipaksa; lihat status box, reconnect |
| `dial tcp <IP>:443: i/o timeout` sesekali, lalu normal | VLESS tanpa multiplex = 1 koneksi TCP per flow; beberapa handshake kalah cepat | Normal (dilabeli "sementara"). Kalau sering, aktifkan mux di server |
| Status `Menyambung ulang...` terus | Server/jalur lambat, atau split tunnel allowlist versi lama | Pastikan versi terbaru; cek server |
| VPN mati sendiri saat app ditutup | Penghemat daya OEM | Atur "Tidak dibatasi", kunci app di Recents, jangan swipe |
| File `.pantunnel` 0 byte | `openOutputStream()` mengembalikan `null` | Sudah ditangani; pakai versi terbaru |
| Tap link `vless://` tidak membuka app | Deep link belum terdaftar | Sudah terdaftar di manifest; uji dari browser/Telegram |
| Split tunnel hanya menampilkan app sistem | `QUERY_ALL_PACKAGES` hilang (Android 11+) | Jangan hapus izin itu |
| "Jaringan ini IPv6-only — PanTunnel butuh IPv4" | TUN hanya klaim `0.0.0.0/0` + DNS `ipv4_only`; operator IPv6-only tanpa CLAT/NAT64 tidak bisa dipakai | Pindah ke Wi-Fi/jaringan yang punya IPv4 |
| Export log tidak ada UUID | Redaksi `DiagnosticsReport` | Memang disengaja |

Performa yang dijaga: RAM idle < 120 MB PSS, APK arm64 < 10 MB, connect < 5 detik, tanpa wake lock.

---

## Lisensi

MIT — lihat [LICENSE](LICENSE).

Komponen pihak ketiga: [sing-box](https://github.com/SagerNet/sing-box) (GPL-3.0, dipakai sebagai
`libbox.aar`), AndroidX (Apache-2.0).