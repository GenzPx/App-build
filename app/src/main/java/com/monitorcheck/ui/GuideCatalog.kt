package com.monitorcheck.ui

data class GuideEntry(
    val route: String,
    val title: String,
    val purpose: String,
    val howItWorks: String,
    val limitation: String? = null
)

/**
 * Central catalogue of every menu in the app: what it is for, how it really
 * gets its data, and which Android limitation applies. The Guide screen and the
 * pre-open explanation dialogs both read from this single list, so a new menu
 * only needs one entry here to be documented everywhere.
 */
object GuideCatalog {
    val entries = listOf(
        GuideEntry("dashboard", "Dashboard", "Ringkasan kesehatan perangkat live.", "Semua kartu membaca satu sample bersama dari monitoring engine terpusat. Kartu bisa di-enable/disable dan diurutkan dari Settings."),
        GuideEntry("live", "Live Monitor", "Semua graph realtime di satu halaman: CPU total, per-core, RAM, network, battery, thermal dan FPS.", "Series diambil dari engine terpusat (tanpa poller tambahan). FPS diukur via Choreographer hanya selama halaman terbuka. Setiap graph mencantumkan sumber datanya.", "FPS hanya untuk frame aplikasi ini sendiri — Android tidak mengekspos FPS aplikasi lain."),
        GuideEntry("cpu", "CPU", "Usage, frekuensi, governor dan topologi core.", "Utilisasi dihitung dari delta /proc/stat (metode yang sama dengan top); frekuensi dari sysfs cpufreq."),
        GuideEntry("gpu", "GPU", "Vendor, renderer, versi GL ES, extensions dan Vulkan.", "Identitas dibaca lewat konteks EGL + OpenGL ES off-screen sungguhan.", "GPU load/clock bukan API publik Android — hanya tampil bila vendor mengeksposnya, selain itu ditandai Unsupported."),
        GuideEntry("memory", "Memory", "RAM total/used/available, swap, zRAM dan low-memory state.", "ActivityManager.MemoryInfo dan /proc/meminfo."),
        GuideEntry("battery", "Battery", "Level, arus, tegangan, suhu, health dan histori lokal.", "BatteryManager dan node kernel. Histori direkam maksimal satu sample per menit.", "Cycle count dan design capacity sering tidak diekspos — ditulis Unavailable, tidak dikarang."),
        GuideEntry("thermal", "Thermal", "Semua thermal zone terbaca, trip point dan status throttling.", "Membaca /sys/class/thermal plus PowerManager thermal status. Status throttling dari trip point dilabeli heuristik."),
        GuideEntry("sensors", "Sensors", "Inventaris sensor lengkap dengan live value.", "SensorManager; listener hanya aktif selama detail sensor terbuka dan dilepas saat halaman ditutup."),
        GuideEntry("storage", "Storage", "Volume, partisi, I/O counter, analyzer folder dan duplikat.", "StorageManager/StatFs, /proc/mounts dan /proc/diskstats. Scan read-only dan dapat dibatalkan.", "Tidak ada penghapusan otomatis; operasi destruktif selalu minta konfirmasi. SMART tidak ada di Android — tidak dipalsukan."),
        GuideEntry("fps", "Display & FPS", "Resolusi, refresh rate, HDR dan FPS render loop aplikasi ini.", "DisplayManager untuk info panel; FPS via Choreographer vsync callback.", "Android tidak mengekspos FPS aplikasi lain ke aplikasi biasa."),
        GuideEntry("tasks", "Task manager", "Proses, service dan aktivitas aplikasi.", "ActivityManager dan UsageStatsManager.", "Android 8+ membatasi daftar proses ke aplikasi ini sendiri; force stop aplikasi lain butuh permission system — ditawarkan buka App Info."),
        GuideEntry("apps", "Applications", "Semua aplikasi terinstall dengan versi, SDK, ukuran dan tanggal.", "PackageManager; ukuran detail butuh Usage Access."),
        GuideEntry("network", "Network", "Koneksi aktif, interface, Wi-Fi, mobile, tools dan per-app usage.", "ConnectivityManager, WifiManager, TrafficStats dan /proc/net. Semua network tools hanya jalan saat ditekan — tidak ada scanning otomatis.", "SSID/BSSID butuh izin lokasi; MAC dirandom oleh Android 6+."),
        GuideEntry("history", "History & trends", "Grafik metric jangka panjang dan temuan pola.", "Disimpan lokal, paling banyak satu sample per menit, dengan retensi yang bisa diatur."),
        GuideEntry("alerts", "Threshold alerts", "Notifikasi saat ambang CPU/RAM/suhu/baterai/storage terlewati.", "Hanya mengevaluasi reading real dari engine dan memakai cooldown."),
        GuideEntry("overlay", "Floating HUD", "Metrik mengambang di atas aplikasi lain.", "Butuh izin Draw over other apps.", "FPS aplikasi lain tetap tidak bisa dibaca — yang tampil adalah metrik sistem yang sah."),
        GuideEntry("stress", "Stress test", "Beban CPU nyata dengan log suhu dan frekuensi.", "Worker thread melakukan komputasi sungguhan; berhenti otomatis pada batas suhu.", "Perangkat akan panas dan baterai cepat turun selama test."),
        GuideEntry("power", "Doze & background activity", "Status Doze, app standby dan aktivitas background.", "PowerManager dan UsageStatsManager.", "Riwayat wakelock per aplikasi dapat dibatasi ROM — ditandai restricted bila begitu."),
        GuideEntry("device", "Device information", "Manufacturer, model, codename, SoC, patch dan capability flags.", "android.os.Build dan PackageManager feature flags."),
        GuideEntry("kernel", "Kernel", "Versi kernel, uptime, boot time, cmdline dan verified boot.", "System properties dan /proc.", "Beberapa node /proc bisa dibatasi SELinux pada ROM tertentu."),
        GuideEntry("selinux", "SELinux", "Status enforcing/permissive dan context proses ini.", "Read-only — aplikasi tidak pernah mengubah state SELinux."),
        GuideEntry("binder", "Binder", "Diagnostik IPC Binder yang terbaca.", "Membaca node debugfs bila tersedia.", "Umumnya unmounted/di-protect SELinux — bila begitu ditampilkan pesan restricted yang jelas."),
        GuideEntry("drivers", "Drivers", "Ketersediaan driver per subsystem (GPU, audio, camera, WiFi, BT, USB, dsb).", "Public API per subsystem plus upaya baca /proc/modules yang jujur.", "/proc/modules biasanya butuh root — status dilaporkan apa adanya."),
        GuideEntry("display", "Display", "Resolusi, density, refresh rate, HDR, color mode dan brightness.", "DisplayManager; supported modes tampil di API 30+."),
        GuideEntry("permissions", "Permission inspector", "Permission per aplikasi dikelompokkan per kategori.", "Untuk aplikasi ini: status Granted/Denied/Not requested via checkSelfPermission. Untuk aplikasi lain: hanya daftar requested.", "Grant state aplikasi lain dan history penggunaan adalah system-only — tidak dikarang."),
        GuideEntry("scanner", "Pattern Scanner", "Heuristik lokal untuk APK, file, folder dan aplikasi terinstall.", "Hash SHA-256, metadata paket, fakta sertifikat, permission manifest dan struktur archive — semuanya lokal. Database matching dapat diimport dan default kosong.", "Bukan antivirus — tidak ada signature database bawaan, cloud reputation, atau sandbox."),
        GuideEntry("diagnosis", "Device diagnosis", "Scan gabungan hardware dan semua aplikasi sekali tekan.", "Menggabungkan reading engine saat ini dengan Pattern Scanner."),
        GuideEntry("logs", "Logs & crashes", "Logcat aplikasi ini dan crash report lokal.", "Dump + live tail dengan filter priority; crash ditangkap lokal tanpa upload.", "Sejak Android 4.1+ aplikasi biasa hanya bisa membaca log UID-nya sendiri."),
        GuideEntry("report", "Export report", "Laporan TXT lengkap semua section plus snapshot realtime.", "Nilai yang tidak tersedia ditulis Unavailable / Restricted by Android — tidak pernah diisi angka palsu."),
        GuideEntry("guide", "Guide App", "Panduan semua menu beserta batasan Android per fitur.", "Tap kartu untuk penjelasan; katalog yang sama dipakai dialog penjelasan sebelum membuka menu teknis."),
        GuideEntry("credits", "Credits", "Tentang aplikasi: versi, developer, lisensi, stack dan prinsip data.", "Halaman statis — tidak ada network request; tombol GitHub hanya membuka browser."),
        GuideEntry("settings", "Settings", "Interval polling, Low Resource Mode, notifikasi, widget dashboard, alerts dan tema.", "Semua preferensi disimpan lokal via DataStore.")
    )

    fun forRoute(r: String) = entries.firstOrNull { it.route == r }
        ?: GuideEntry(r, r, "Menu diagnostik", "Buka untuk melihat data.")
}
