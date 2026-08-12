# Monitored Check — Roadmap

Daftar ide fitur yang sudah disepakati untuk dikerjakan, plus kandidat lain.

**Status branch `feature/all-monitoring-suite`:** paket power-user, threshold alerts,
home-screen widgets, guide, diagnosis, history, per-app network usage, stress test,
Quick Settings tile, floating HUD, dan Doze/background analyzer sudah memiliki
implementasi awal yang dapat dibuild. Item yang masih tertulis di bawah dapat dipakai
sebagai backlog penyempurnaan UI, OEM compatibility, dan pengujian perangkat nyata.

Aturan yang berlaku untuk **semua** item di sini: tidak boleh ada data palsu. Kalau
Android tidak mengizinkan suatu data, fitur tetap dibuat tapi menampilkan status
sebenarnya (Unavailable / Unsupported / Permission Required / Restricted by Android),
bukan angka karangan.

Legenda kesulitan: 🟢 ringan · 🟡 sedang · 🔴 berat

---

## Paket 1 — Power User

Fitur yang bikin aplikasi dipakai terus-menerus, bukan cuma dibuka sekali.

### 1.1 Floating Overlay Monitor 🔴
Bubble melayang di atas aplikasi lain (game, YouTube) berisi CPU/RAM/FPS/suhu realtime.

- API: `SYSTEM_ALERT_WINDOW` + `WindowManager.TYPE_APPLICATION_OVERLAY`
- Bisa digeser, atur opacity, pilih metrik, mode ringkas/detail
- Catatan: izin harus diaktifkan manual user. HP Xiaomi/Oppo/Vivo punya lapisan izin
  tambahan — perlu penjelasan + tombol pintasan ke halaman izin OEM
- FPS di overlay tetap mengukur render loop milik aplikasi ini, bukan game lain
  (Android tidak mengizinkan). Harus dinyatakan jelas di UI

### 1.2 Alert / Peringatan Ambang 🟡
Notifikasi otomatis saat metrik melewati batas yang diatur user.

- Contoh: suhu > 45°C, baterai > 42°C saat charging, RAM > 90%, storage < 10%
- Ambang bisa diatur, bisa dimatikan per-jenis
- Riwayat alert tersimpan lokal
- Jalan lewat foreground service yang sudah ada, tanpa polling tambahan

### 1.3 Per-App Network Usage 🟡
Pemakaian kuota internet per aplikasi.

- API: `NetworkStatsManager` (butuh Usage Access — sudah tersedia)
- Rentang harian/mingguan/bulanan, pisah Wi-Fi vs seluler
- Urutkan berdasar pemakaian, cari per aplikasi

### 1.4 Quick Settings Tile 🟢
Toggle monitoring langsung dari panel notifikasi.

- API: `TileService` (API 24+)
- Tile menampilkan status aktif/nonaktif + metrik singkat

---

## Paket 2 — Cepat Jadi

### 2.1 Bahasa Indonesia 🟡
Seluruh teks UI saat ini bahasa Inggris dan di-hardcode di Kotlin.

- Ekstrak semua string ke `values/strings.xml`
- Terjemahan di `values-in/strings.xml`, otomatis ikut bahasa HP
- Pekerjaan terbesar ada di ekstraksi, bukan penerjemahan

### 2.2 Home Screen Widget 🟡
Widget 2x1 / 4x1 berisi CPU, RAM, baterai, suhu.

- API: `AppWidgetProvider` + Glance
- Update berkala hemat baterai (jangan tiap detik)

### 2.3 Export CSV + JSON 🟢
Sekarang hanya TXT.

- CSV supaya bisa dibuka di Excel/Sheets dan dibuatkan grafik sendiri
- JSON untuk diproses otomatis
- Termasuk export riwayat baterai

---

## Paket 3 — Data Baru

### 3.1 Codec & Media Info 🟢
- API: `MediaCodecList`
- Daftar encoder/decoder (H.264, HEVC, AV1, VP9), resolusi maksimum,
  hardware-accelerated atau tidak, profile & level
- Berguna untuk tahu apakah HP sanggup memutar 4K / AV1

### 3.2 Camera Detail Lengkap 🟡
Saat ini masih ringkas.

- Ukuran sensor, aperture, focal length, rentang ISO, rentang exposure
- Resolusi video maksimum, dukungan RAW, OIS, semua resolusi foto
- API: `CameraCharacteristics` (sudah dipakai, tinggal diperluas)

### 3.3 Riwayat Suhu & CPU 30 Hari 🟡
Sekarang baru baterai yang punya riwayat panjang.

- Simpan suhu dan pemakaian CPU dengan pola yang sama
- Berguna melihat pola thermal throttling dari waktu ke waktu
- Perlu hati-hati soal ukuran database dan biaya penulisan

---

## Ide Tambahan

### 4.1 Perbandingan Benchmark 🟢
Simpan hasil benchmark lama dan bandingkan antar-run. "Skor turun 15% dari minggu
lalu" bisa jadi tanda throttling atau baterai menua. Murni lokal, tanpa leaderboard
online.

### 4.2 Search Global 🟡
Satu kotak pencarian untuk menemukan info atau setelan apa pun di seluruh aplikasi.

### 4.3 Stress Test 🟡
Bebani CPU/GPU beberapa menit sambil merekam suhu dan frekuensi, lalu tampilkan
grafik penurunan performa. Ini cara jujur mengukur thermal throttling. Wajib ada
peringatan bahwa HP akan panas, dan berhenti otomatis di suhu bahaya.

### 4.4 Deteksi Wakelock & Doze 🟡
`PowerManager.isDeviceIdleMode`, status app standby bucket, daftar app yang
dikecualikan dari optimasi baterai. Membantu mencari penyebab baterai boros.

### 4.5 Perbandingan Sebelum/Sesudah 🟢
Ambil snapshot sistem, lakukan sesuatu (tutup app, restart), ambil snapshot lagi,
lalu tampilkan selisihnya berdampingan.

### 4.6 Info Layar Sentuh 🟡
Test multi-touch dan tampilkan touch sampling rate. Berguna untuk gamer.

### 4.7 Test Sensor Interaktif 🟡
Bukan cuma menampilkan angka sensor, tapi memandu pengujian: kompas berputar,
level air dari akselerometer, test proximity, test getaran.

### 4.8 Dark Mode Terjadwal / AMOLED Hitam Pekat 🟢
Tema hitam murni (#000000) untuk hemat baterai di layar AMOLED, dan penjadwalan
otomatis ikut matahari terbit/terbenam.

### 4.9 Backup & Restore Setelan 🟢
Export seluruh konfigurasi (widget, ambang alert, interval) ke satu file, supaya
mudah dipindah saat ganti HP.

### 4.10 Ekspor Grafik jadi Gambar 🟢
Simpan grafik apa pun sebagai PNG untuk dibagikan.

### 4.11 Riwayat Pemindaian Pattern Scanner 🟢
Simpan hasil scan sebelumnya, tandai kalau ada aplikasi yang skor risikonya naik
setelah update.

### 4.12 Mode Kompak Dashboard 🟢
Tampilan padat untuk layar kecil: kartu jadi lebih rapat, teks lebih ringkas.

---

## Yang TIDAK akan dibuat

Bukan karena sulit, tapi karena melanggar prinsip aplikasi ini:

- **"RAM booster" / force-stop massal.** Android sudah mengelola memori sendiri.
  Menutup paksa aplikasi justru membuat boros baterai karena aplikasi restart. Fitur
  semacam ini menipu pengguna dengan angka "RAM dibebaskan" yang tidak bermakna
- **Skor benchmark yang diskalakan ke tabel referensi rahasia.** Skor harus berupa
  throughput nyata yang bisa ditelusuri
- **Leaderboard online / upload hasil.** Melanggar janji privasi
- **Klaim antivirus.** Pattern Scanner tetap disebut heuristik, bukan antivirus
- **Fitur yang butuh root.** Aplikasi harus berfungsi penuh tanpa root
- **Estimasi kesehatan baterai karangan.** Kalau kernel tidak melaporkan
  `charge_full_design`, statusnya tetap Unavailable
