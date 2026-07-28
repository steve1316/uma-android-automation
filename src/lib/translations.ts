import { useContext, useCallback } from "react"
import { GeneralMiscContext } from "../context/BotStateContext"

export const TRANSLATIONS: Record<string, string> = {
    // Drawer Content / Sidebar Sections & Items
    Overview: "Ikhtisar",
    Home: "Beranda",
    Settings: "Pengaturan",
    "Ask the Docs": "Tanya Dokumen",
    Gameplay: "Permainan",
    Training: "Pelatihan",
    "Training Events": "Acara Pelatihan",
    Racing: "Balapan",
    Schedule: "Jadwal",
    Skills: "Keterampilan",
    Scenarios: "Skenario",
    "Scenario Overrides": "Pengecualian Skenario",
    Integrations: "Integrasi",
    Discord: "Discord",
    LLM: "LLM",
    Tools: "Alat",
    "Event Log": "Log Acara",
    Debug: "Debug",
    Language: "Bahasa",
    "Select Language": "Pilih Bahasa",

    // Home Page & Statuses
    "Not Ready": "Belum Siap",
    "Select a Scenario": "Pilih Skenario",
    Start: "Mulai",
    Stop: "Berhenti",
    "Everything looks good and ready to go!": "Semua terlihat baik dan siap dijalankan!",
    "Select a Scenario to start from the selector button dropdown.": "Pilih Skenario untuk memulai dari tombol dropdown selektor.",
    "A scenario must be selected before starting the bot. Tap the dropdown on this Start button to pick one.":
        "Skenario harus dipilih sebelum menjalankan bot. Ketuk dropdown pada tombol Mulai ini untuk memilih.",
    "Saving settings before starting bot...": "Menyimpan pengaturan sebelum menjalankan bot...",
    "Settings saved successfully, starting bot...": "Pengaturan berhasil disimpan, menjalankan bot...",
    "Failed to save settings before starting:": "Gagal menyimpan pengaturan sebelum memulai:",

    // Settings Main Page
    GAMEPLAY: "PERMAINAN",
    SCENARIO: "SKENARIO",
    INTEGRATIONS: "INTEGRASI",
    TOOLS: "ALAT",
    MISC: "LAIN-LAIN",
    "WAIT DELAY": "JEDA WAKTU TUNGGU",
    "DATA MANAGEMENT": "MANAJEMEN DATA",
    "Stop before Finals": "Berhenti Sebelum Final",
    "Pause to buy skills before the final races": "Jeda untuk membeli keterampilan sebelum balapan final",
    "Enable Claw Machine Attempt": "Aktifkan Upaya Mesin Cakar",
    "Attempt to complete the claw machine instead of stopping": "Mencoba menyelesaikan mesin cakar daripada berhenti",
    "Enable Swipe-Based Scrolling": "Aktifkan Gulir Berbasis Geseran",
    "Scroll lists by swiping instead of detecting the in-game scrollbar. Enable this if the bot cannot scroll lists normally. This may or may not work depending on the device.":
        "Gulir daftar dengan menggeser layar daripada mendeteksi scrollbar dalam game. Aktifkan ini jika bot tidak bisa menggulir daftar secara normal. Ini mungkin berfungsi atau tidak tergantung pada perangkat.",
    "Enable Settings Display in Message Log": "Aktifkan Tampilan Pengaturan di Log Pesan",
    "Show current bot configuration in the message log": "Tampilkan konfigurasi bot saat ini di dalam log pesan",
    "Wait Delay": "Jeda Tunggu",
    "Sets the delay between actions and imaging operations. Lowering this will make the bot run much faster at the risk of the bot losing track of its location after loading/connecting screens.":
        "Mengatur jeda waktu antara tindakan dan operasi pengambilan gambar. Menurunkan nilai ini akan membuat bot berjalan jauh lebih cepat dengan risiko bot kehilangan jejak lokasinya setelah layar pemuatan/koneksi.",
    "Dialog Wait Delay": "Jeda Tunggu Dialog",
    "Sets the delay between clicking a button that opens dialog and actually handling the dialog. Lowering this will make the bot run faster at an increased risk of the bot incorrectly handling dialogs that pop up.":
        "Mengatur jeda waktu antara mengklik tombol yang membuka dialog dan penanganan dialog tersebut. Menurunkan nilai ini akan membuat bot berjalan lebih cepat dengan peningkatan risiko bot salah menangani dialog yang muncul.",
    "Settings Management": "Manajemen Pengaturan",
    "Import and export settings from JSON file or access the app's data directory.": "Impor dan ekspor pengaturan dari file JSON atau akses direktori data aplikasi.",
    Import: "Impor",
    "Load settings from JSON": "Muat pengaturan dari JSON",
    Export: "Ekspor",
    "Save settings to JSON": "Simpan pengaturan ke JSON",
    Data: "Data",
    "Open folder": "Buka folder",
    Reset: "Reset",
    "Restore defaults": "Pulihkan default",
    "Settings Imported": "Pengaturan Diimpor",
    "Settings have been imported successfully.": "Pengaturan telah berhasil diimpor.",
    "Reset Settings to Default": "Reset Pengaturan ke Default",
    "Are you sure you want to reset all settings to their default values? This action cannot be undone and will overwrite your current configuration.":
        "Apakah Anda yakin ingin mereset semua pengaturan ke nilai defaultnya? Tindakan ini tidak dapat dibatalkan dan akan menimpa konfigurasi Anda saat ini.",
    Cancel: "Batal",
    "Reset Settings to Default...": "Reset Pengaturan ke Default...",

    // Training Settings Page
    "Training Settings": "Pengaturan Pelatihan",
    "Profile Selector": "Selektor Profil",
    "Profiles constitute only the Training settings and stat targets.": "Profil hanya mencakup pengaturan Pelatihan dan target statistik.",
    Priorities: "Prioritas",
    "Sync Priorities": "Sinkronkan Prioritas",
    Blacklist: "Daftar Hitam",
    "Select which stats to exclude from training. These stats will be skipped during training sessions.":
        "Pilih statistik mana yang ingin dikecualikan dari pelatihan. Statistik ini akan dilewati selama sesi pelatihan.",
    Prioritization: "Prioritas Pelatihan",
    "Select the priority order of the stats. The stats will be trained in the order they are selected. If none are selected, then the default order will be used.":
        "Pilih urutan prioritas statistik. Statistik akan dilatih berdasarkan urutan pemilihan. Jika tidak ada yang dipilih, urutan default akan digunakan.",
    "Event Choice Prioritization": "Prioritas Pilihan Acara",
    "Select the priority order of stats used when scoring in-game event choices. Events typically grant flat stat gains, so a different ordering than regular training may be optimal.":
        "Pilih urutan prioritas statistik yang digunakan saat menilai pilihan acara dalam game. Acara biasanya memberikan peningkatan statistik datar, jadi urutan yang berbeda dari pelatihan biasa mungkin lebih optimal.",
    "Summer Training Prioritization": "Prioritas Pelatihan Musim Panas",
    "Select the priority order of stats used during Summer Training. Facility levels are maxed during summer, so a different ordering than regular training may be optimal.":
        "Pilih urutan prioritas statistik yang digunakan selama Pelatihan Musim Panas. Tingkat fasilitas dimaksimalkan selama musim panas, jadi urutan yang berbeda dari pelatihan biasa mungkin lebih optimal.",
    "Set Maximum Failure Chance": "Atur Peluang Kegagalan Maksimum",
    "Set the maximum acceptable failure chance for training sessions. Training with higher failure rates will be avoided.":
        "Atur peluang kegagalan maksimum yang dapat diterima untuk sesi pelatihan. Pelatihan dengan tingkat kegagalan yang lebih tinggi akan dihindari.",
    "Disable Training on Maxed Stats": "Nonaktifkan Pelatihan pada Statistik Maksimum",
    "When enabled, training will be skipped for stats that have reached their maximum value.": "Jika aktif, pelatihan akan dilewati untuk statistik yang telah mencapai nilai maksimumnya.",
    "Enable Riskier Training": "Aktifkan Pelatihan Lebih Berisiko",
    "When enabled, trainings with high main stat gains will use a separate, higher maximum failure chance threshold.":
        "Jika aktif, pelatihan dengan peningkatan statistik utama yang tinggi akan menggunakan ambang batas peluang kegagalan maksimum terpisah yang lebih tinggi.",
    "Minimum Main Stat Gain Threshold": "Ambang Batas Minimum Peningkatan Statistik Utama",
    "When a training's main stat gain meets or exceeds this value, it will be considered for risky training.":
        "Ketika peningkatan statistik utama dari pelatihan memenuhi atau melebihi nilai ini, pelatihan tersebut akan dipertimbangkan untuk pelatihan berisiko.",
    "Risky Training Maximum Failure Chance": "Peluang Kegagalan Maksimum Pelatihan Berisiko",
    "Set the maximum acceptable failure chance for risky training sessions with high main stat gains.":
        "Atur peluang kegagalan maksimum yang dapat diterima untuk sesi pelatihan berisiko dengan peningkatan statistik utama yang tinggi.",
    "Minimum Energy to Train": "Energi Minimum untuk Berlatih",
    "Rest instead of training when energy falls below this, even if the failure chances are low enough to train. 0 disables it. Summer Training and the Finale always ignore this.":
        "Istirahat alih-alih berlatih ketika energi turun di bawah nilai ini, meskipun peluang kegagalan cukup rendah untuk berlatih. 0 menonaktifkannya. Pelatihan Musim Panas dan Babak Final selalu mengabaikan ini.",
    "Train Wit Instead of Resting": "Latih Wit Alih-alih Istirahat",
    "When enabled, Wit gets its own custom failure chance threshold.": "Jika aktif, Wit mendapatkan ambang batas peluang kegagalannya sendiri.",
    "Wit Maximum Failure Chance": "Peluang Kegagalan Maksimum Wit",
    "The maximum acceptable failure chance for Wit training before doing something else.": "Peluang kegagalan maksimum yang dapat diterima untuk pelatihan Wit sebelum melakukan aktivitas lain.",
    "Minimum Wit Main Stat Gain Threshold": "Ambang Batas Minimum Peningkatan Statistik Utama Wit",
    "When the Wit training's main stat gain meets or exceeds this value, Wit uses its higher maximum failure chance.":
        "Ketika peningkatan statistik utama dari pelatihan Wit memenuhi atau melebihi nilai ini, Wit akan menggunakan peluang kegagalan maksimumnya yang lebih tinggi.",
    "Prioritize Skill Hints": "Prioritaskan Petunjuk Keterampilan",
    "When enabled, the bot will prioritize acquiring skill hints, bypassing stat prioritization and blacklist, while still being constrained by the failure chance thresholds.":
        "Jika aktif, bot akan memprioritaskan perolehan petunjuk keterampilan, mengabaikan prioritas statistik dan daftar hitam, sambil tetap dibatasi oleh ambang batas peluang kegagalan.",
    "Must Rest before Summer": "Wajib Istirahat Sebelum Musim Panas",
    "Optimizes June Late Phase in Classic and Senior Years for Summer Training. If Energy < 70%, it will Rest. If Energy >= 70% and Mood < Great, it will recover Mood. If Energy >= 70% and Mood is Great, it will train Wit.":
        "Mengoptimalkan Fase Akhir Juni di Tahun Klasik dan Senior untuk Pelatihan Musim Panas. Jika Energi < 70%, ia akan Istirahat. Jika Energi >= 70% dan Mood < Bagus, ia akan memulihkan Mood. Jika Energi >= 70% dan Mood adalah Bagus, ia akan melatih Wit.",
    "Train Wit During Finale": "Latih Wit Selama Final",
    "When enabled, the bot will train Wit during URA finale turns (73, 74, 75) instead of recovering energy or mood, even if the failure chance is high.":
        "Jika aktif, bot akan melatih Wit selama giliran final URA (73, 74, 75) alih-alih memulihkan energi atau suasana hati (mood), meskipun peluang kegagalannya tinggi.",
    "Weight Score by Training Level": "Beri Bobot Skor Berdasarkan Tingkat Pelatihan",
    "When enabled (Year 2+), the bot reads each training's level (1-5) via OCR and boosts the score for trainings whose stat sits in the top 3 of your Stat Prioritization list. Helps the bot stick with stats you've invested in. OCR is skipped during Pre-Debut, Junior, and Summer.":
        "Jika aktif (Tahun 2+), bot membaca tingkat pelatihan (1-5) melalui OCR dan meningkatkan skor untuk pelatihan yang statistiknya berada di 3 teratas daftar Prioritas Statistik Anda. Membantu bot tetap fokus pada statistik yang telah Anda investasikan. OCR dilewati selama Pra-Debut, Junior, dan Musim Panas.",
    "Enable Rainbow Training Bonus": "Aktifkan Bonus Pelatihan Pelangi",
    "When enabled (Year 2+), rainbow trainings receive a significant bonus to their score, making them more likely to be selected. This is highly dependent on device configuration and may result in false positives.":
        "Jika aktif (Tahun 2+), pelatihan pelangi menerima bonus signifikan pada skornya, membuatnya lebih mungkin dipilih. Ini sangat bergantung pada konfigurasi perangkat dan dapat menghasilkan deteksi positif palsu.",
    "Prioritize Near-Max Friendship Bars": "Prioritaskan Batas Pertemanan Dekat Maksimum",
    "When enabled (Year 2+), trainings with multiple green/blue friendship bars close to maxing receive an anticipatory rainbow multiplier, helping the bot favor them so the bars cross into orange and unlock rainbow training on later turns. Does not stack with the actual rainbow bonus.":
        "Jika aktif (Tahun 2+), pelatihan dengan beberapa bilah pertemanan hijau/biru yang mendekati maksimal akan menerima pengali pelangi antisipatif, membantu bot untuk memilihnya agar bilah tersebut melintasi ke warna oranye dan membuka pelatihan pelangi pada giliran berikutnya. Tidak menumpuk dengan bonus pelangi yang sebenarnya.",
    "Enable Training Analysis Validation": "Aktifkan Validasi Analisis Pelatihan",
    "When enabled, the bot will validate the current selected stat during training analysis. This helps prevent the bot from accidentally training a stat during analysis at the cost of a significant increase in scenario completion time.":
        "Jika aktif, bot akan memvalidasi statistik yang dipilih saat analisis pelatihan. Ini membantu mencegah bot melatih statistik secara tidak sengaja selama analisis dengan konsekuensi peningkatan waktu penyelesaian skenario yang signifikan.",
    "Enable YOLO Stat Detection": "Aktifkan Deteksi Statistik YOLO",
    "When enabled, the bot will use a custom YOLOv8 model for high-precision stat gain detection. This replaces the standard OCR/Template matching for stat gains.":
        "Jika aktif, bot akan menggunakan model YOLOv8 khusus untuk deteksi peningkatan statistik presisi tinggi. Ini menggantikan pencocokan OCR/Templat standar untuk peningkatan statistik.",
    "Preferred Distance Override": "Pengecualian Jarak Pilihan",
    "Set the preferred race distance for training targets. Auto picks based on character aptitudes.":
        "Mengatur jarak balapan pilihan untuk target pelatihan. Auto memilih berdasarkan kemampuan karakter.",
    "Disable Stat Targets": "Nonaktifkan Target Statistik",
    "When enabled, all per-distance stat targets below are ignored. Every stat is treated as having a target equal to its in-game stat cap, so the bot will keep pushing your top priority stats even after they would normally be considered 'done.' Useful when you want strict adherence to your Stat Prioritization list.":
        "Jika aktif, semua target statistik per-jarak di bawah ini diabaikan. Setiap statistik diperlakukan memiliki target yang sama dengan batas statistik in-game, sehingga bot akan terus meningkatkan statistik prioritas utama Anda bahkan setelah biasanya dianggap 'selesai.' Berguna saat Anda ingin kepatuhan ketat pada daftar Prioritas Statistik Anda.",
    "Read Stat Caps from Screen": "Baca Batas Statistik dari Layar",
    "When enabled, the bot reads each stat's live cap every turn, so cap increases from sparks, inheritance, etc. are respected. Disable to always use the fixed per-scenario caps if the reading ever misbehaves.":
        "Jika aktif, bot membaca batas langsung setiap statistik setiap giliran, sehingga peningkatan batas dari percikan, warisan, dll., dihormati. Nonaktifkan untuk selalu menggunakan batas tetap per-skenario jika pembacaan pernah bermasalah.",
    "Stat Targets by Distance": "Target Statistik Berdasarkan Jarak",
    "Set target values for each stat based on race distance.": "Mengatur nilai target untuk setiap statistik berdasarkan jarak balapan.",
    "Sprint Distance": "Jarak Sprint",
    "Mile Distance": "Jarak Mile",
    "Medium Distance": "Jarak Medium",
    "Long Distance": "Jarak Jauh",
    "Year Milestones": "Pencapaian Tahun",
    "Training Year Milestone Targets": "Target Pencapaian Tahun Pelatihan",
    "Controls how aggressively the bot paces stat training during the Pre-Debut, Junior and Classic Years.":
        "Mengatur seberapa agresif bot mengatur ritme pelatihan statistik selama Tahun Pra-Debut, Junior, dan Klasik.",
    "Year Milestone Pacing": "Pengaturan Ritme Pencapaian Tahun",
    "End of Junior Year Milestone": "Pencapaian Akhir Tahun Junior",
    "Percentage of the primary stat targets to aim for by the end of Junior Year.": "Persentase target statistik utama yang ingin dicapai pada akhir Tahun Junior.",
    "End of Classic Year Milestone": "Pencapaian Akhir Tahun Klasik",
    "Percentage of the primary stat targets to aim for by the end of Classic Year.": "Persentase target statistik utama yang ingin dicapai pada akhir Tahun Klasik.",

    // Training Event Settings Page
    "Training Event Settings": "Pengaturan Acara Pelatihan",
    General: "Umum",
    "Prioritize Energy Options": "Prioritaskan Pilihan Energi",
    "When enabled, the bot will prioritize training event choices that provide energy recovery or avoid energy consumption, helping to maintain optimal energy levels for training sessions.":
        "Jika aktif, bot akan memprioritaskan pilihan acara pelatihan yang memberikan pemulihan energi atau menghindari konsumsi energi, membantu mempertahankan tingkat energi yang optimal untuk sesi pelatihan.",
    "OCR Recognition Settings": "Pengaturan Pengenalan OCR",
    "Configure settings for detecting and recognizing Training Event titles using OCR. These settings only affect the Training Event recognition process.":
        "Konfigurasikan pengaturan untuk mendeteksi dan mengenali judul Acara Pelatihan menggunakan OCR. Pengaturan ini hanya mempengaruhi proses pengenalan Acara Pelatihan.",
    "Enable Automatic OCR Retry for Training Events": "Aktifkan Upaya Ulang OCR Otomatis untuk Acara Pelatihan",
    "When enabled, the bot will automatically retry OCR detection with adjusted settings if the initial attempt for a training event title fails or has low confidence.":
        "Jika aktif, bot akan secara otomatis mencoba kembali deteksi OCR dengan pengaturan yang disesuaikan jika upaya awal untuk judul acara pelatihan gagal atau memiliki tingkat kepercayaan yang rendah.",
    "OCR Confidence for Training Events": "Tingkat Kepercayaan OCR untuk Acara Pelatihan",
    "The minimum confidence level required for a Training Event title to be considered a match. Higher values ensure more accurate recognition but may lead to more missed events.":
        "Tingkat kepercayaan minimum yang diperlukan agar judul Acara Pelatihan dianggap cocok. Nilai yang lebih tinggi memastikan pengenalan yang lebih akurat tetapi dapat menyebabkan lebih banyak acara terlewatkan.",
    "Hide OCR String Comparison Results": "Sembunyikan Hasil Perbandingan Teks OCR",
    "If enabled, the bot will suppress detailed logging of individual string similarity scores during training event detection to keep the logs cleaner.":
        "Jika aktif, bot akan menyembunyikan pencatatan log terperinci dari skor kemiripan teks individu selama deteksi acara pelatihan agar log tetap bersih.",
    "Training Event Option Overrides": "Pengecualian Pilihan Acara Pelatihan",
    "Force the bot to select a specific option for character or support training events. Search through all available events and select which option to use. This overrides the normal stat prioritization logic.":
        "Paksa bot untuk memilih opsi tertentu untuk acara pelatihan karakter atau dukungan. Cari melalui semua acara yang tersedia dan pilih opsi mana yang akan digunakan. Ini mengesampingkan logika prioritas statistik normal.",
    "Add event override": "Tambah pengecualian acara",
    "Special Event Overrides": "Pengecualian Acara Khusus",
    "Override the bot's normal stat prioritization for specific training events. These settings bypass the standard weight calculation system.":
        "Mengesampingkan prioritas statistik normal bot untuk acara pelatihan tertentu. Pengaturan ini melewati sistem perhitungan bobot standar.",
    "Holiday Events": "Acara Liburan",
    "Race Result Events": "Acara Hasil Balapan",
    "Training Failure Events": "Acara Kegagalan Pelatihan",
    "Miscellaneous Events": "Acara Lain-Lain",

    // Ask the Docs Chat & LLM Settings
    LLM: "LLM",
    "Ask the Docs Chatbot": "Chatbot Tanya Dokumen",
    "Type your message...": "Ketik pesan Anda...",
    Send: "Kirim",
    "On-device docs search and chat model downloads.": "Pencarian dokumen di dalam perangkat dan unduhan model obrolan.",
    "On-device docs chat powered by the LLM engine.": "Obrolan dokumen di dalam perangkat didukung oleh mesin LLM.",
    "On-device docs chatbot": "Chatbot dokumen di dalam perangkat",
    "Enable Ask the Docs Chatbot": "Aktifkan Chatbot Tanya Dokumen",
    "LLM Settings": "Pengaturan LLM",

    // Missing settings page and general keys
    Behavior: "Perilaku",
    "Behavior overrides specific to each scenario.": "Pengecualian perilaku khusus untuk setiap skenario.",
    "Debug mode, template matching, and diagnostic tests.": "Mode debug, pencocokan templat, dan tes diagnostik.",
    Detection: "Deteksi",
    "Discord notifications when the bot stops.": "Notifikasi Discord ketika bot berhenti.",
    Distance: "Jarak",
    "Event Log Visualizer (Beta)": "Visualizer Log Acara (Beta)",
    "Import logs and view a day-by-day timeline of actions.": "Impor log dan lihat lini masa tindakan hari demi hari.",
    "Near-Max Friendship Boost": "Peningkatan Pertemanan Mendekati Maksimal",
    OK: "OK",
    "Preferred Distance": "Jarak Pilihan",
    "Race solver, recreation, and the unified career calendar.": "Penyelesai balapan, rekreasi, dan kalender karir terpadu.",
    "Racing behavior, retries, and mandatory race handling.": "Perilaku balapan, upaya ulang, dan penanganan balapan wajib.",
    "Rainbow Training Bonus": "Bonus Pelatihan Pelangi",
    Scoring: "Penilaian",
    "Skill purchasing behavior.": "Perilaku pembelian keterampilan.",
    "Stat priorities, training behavior, and customization.": "Prioritas statistik, perilaku pelatihan, dan kustomisasi.",
    "Training Analysis Validation": "Validasi Analisis Pelatihan",
    "Training event preferences and event selection.": "Preferensi acara pelatihan dan pemilihan acara.",
    "YOLO Stat Detection": "Deteksi Statistik YOLO",

    // Racing Settings Page
    "Allow Daily Free Race Retry": "Izinkan Upaya Ulang Balapan Gratis Harian",
    "Allows configuration of automated skill point spending.": "Mengatur konfigurasi pembelian poin keterampilan otomatis.",
    Apply: "Terapkan",
    "Attempt to buy all negative skills (e.g. Firm Conditions x)": "Mencoba membeli semua keterampilan negatif (contoh: Kondisi Kokoh x)",
    CALENDAR: "KALENDER",
    "CLASSIC AND SENIOR YEAR": "TAHUN KLASIK DAN SENIOR",
    "Character Preset": "Preset Karakter",
    "Complete Career on Failure": "Selesaikan Karir Saat Gagal",
    "Critical energy level and consecutive race limits are ignored for the user in-game racing agenda.": "Tingkat energi kritis dan batas balapan berurutan diabaikan untuk agenda balapan in-game pengguna.",
    "Custom Agenda Title": "Judul Agenda Kustom",
    "Custom Agenda Title (Optional)": "Judul Agenda Kustom (Opsional)",
    "Days to Run Extra Races": "Hari untuk Menjalankan Balapan Ekstra",
    "Disable Race Retries": "Nonaktifkan Upaya Ulang Balapan",
    "Enable Farming Fans": "Aktifkan Pencarian Penggemar (Farming Fans)",
    "Enable Skill Point Check": "Aktifkan Pemeriksaan Poin Keterampilan",
    "Enable User In-Game Race Agenda": "Aktifkan Agenda Balapan In-Game Pengguna",
    "Extra races are eligible only on days where current day % value == 0. For example, 5 means days 5, 10, 15, etc. Has no effect when Smart Race Solver is enabled.": "Balapan ekstra hanya memenuhi syarat pada hari di mana nilai % hari saat ini == 0. Misalnya, 5 berarti hari ke-5, 10, 15, dll. Tidak berpengaruh ketika Smart Race Solver aktif.",
    "Force Racing": "Paksa Balapan",
    "How skill spending works": "Cara kerja pembelian keterampilan",
    "If you renamed your agenda in-game, enter the custom title here. Leave blank to use the selected agenda name above.": "Jika Anda mengubah nama agenda Anda di dalam game, masukkan judul kustom di sini. Biarkan kosong untuk menggunakan nama agenda terpilih di atas.",
    "Ignore Consecutive Race Warning": "Abaikan Peringatan Balapan Beruntun",
    "In-Game Race Agenda": "Agenda Balapan In-Game",
    "JUNIOR YEAR": "TAHUN JUNIOR",
    "JUNIOR YEAR STRATEGY": "STRATEGI TAHUN JUNIOR",
    "Junior Year Race Strategy": "Strategi Balapan Tahun Junior",
    "Junior Year Strategy": "Strategi Tahun Junior",
    "Leave blank to use selected agenda name": "Biarkan kosong untuk menggunakan nama agenda terpilih",
    "Limit Extra Races to Agenda": "Batasi Balapan Ekstra Sesuai Agenda",
    "Minimum Energy for Extra Races": "Energi Minimum untuk Balapan Ekstra",
    "Minimum Rainbows to Train Over G1": "Minimum Pelangi untuk Berlatih Dibanding G1",
    "ORIGINAL STRATEGY": "STRATEGI ASLI",
    "On a G1 race day (Classic/Senior years), peek at the trainings first and stay to train when a strong rainbow training is available instead of taking the race.": "Pada hari balapan G1 (tahun Klasik/Senior), intip pelatihan terlebih dahulu dan tetap berlatih ketika pelatihan pelangi yang kuat tersedia alih-alih mengikuti balapan.",
    "Original Race Strategy": "Strategi Balapan Asli",
    "Original Strategy": "Strategi Asli",
    "Per-Distance Strategy": "Strategi Per Jarak",
    "Pick the trainee. Sets the calendar's mandatory races and seeds the Race Solver aptitudes.": "Pilih trainee. Mengatur balapan wajib kalender dan menentukan bakat Race Solver.",
    "Prefer Training on G1 Days": "Lebih Memilih Pelatihan di Hari G1",
    "Purchase All Negative Skills": "Beli Semua Keterampilan Negatif",
    "Purchase skills based on this plan's configuration": "Beli keterampilan berdasarkan konfigurasi rencana ini",
    "Race Behavior": "Perilaku Balapan",
    "Racing Settings": "Pengaturan Balapan",
    "Reset to default": "Reset ke default",
    "SELECT AGENDA": "PILIH AGENDA",
    "Select Agenda": "Pilih Agenda",
    "Set a different race strategy for each track distance. Auto picks the best strategy. Default leaves the in-game strategy alone.": "Atur strategi balapan yang berbeda untuk setiap jarak lintasan. Auto memilih strategi terbaik. Default akan membiarkan strategi dalam game apa adanya.",
    "Sets the calendar's mandatory races": "Mengatur balapan wajib di kalender",
    "Settings changed - recompute the schedule.": "Pengaturan berubah - hitung ulang jadwal.",
    "Skill Plans": "Rencana Keterampilan",
    "Skill Point Threshold": "Ambang Batas Poin Keterampilan",
    "Skip Summer Training for Agenda": "Lewati Pelatihan Musim Panas Demi Agenda",
    "Skip the fan-farming extra race when energy is below this percentage. 0 disables the floor. Only gates the standard fan-farming cadence, never mandatory, scheduled, or solver races.": "Lewati balapan ekstra pencarian penggemar ketika energi berada di bawah persentase ini. 0 menonaktifkan batas bawah. Hanya membatasi ritme pencarian penggemar standar, tidak pernah membatasi balapan wajib, terjadwal, atau hasil solver.",
    "Stop on Mandatory Races": "Berhenti Pada Balapan Wajib",
    "Stop the bot when the skill point threshold is reached": "Hentikan bot saat ambang batas poin keterampilan tercapai",
    Strategy: "Strategi",
    "TRAINEE": "TRAINEE",
    "The best training must have at least this many rainbow supports to train instead of racing the G1.": "Pelatihan terbaik harus memiliki setidaknya sebanyak ini dukungan pelangi untuk berlatih alih-alih melakukan balapan G1.",
    "The in-game race agenda the bot loads when the toggle above is enabled.": "Agenda balapan dalam game yang dimuat bot ketika tombol di atas diaktifkan.",
    "The number of skill points to accumulate before stopping the bot.": "Jumlah poin keterampilan yang harus dikumpulkan sebelum menghentikan bot.",
    "The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond.": "Strategi balapan untuk di-reset setelah Tahun Junior. Bot akan menggunakan strategi ini untuk balapan di Tahun 2 ke atas.",
    "The race strategy to use for all races during Junior Year.": "Strategi balapan yang digunakan untuk semua balapan selama Tahun Junior.",
    "This feature is not made of magic. If you wish to train an uma up for TT or CM, then you should buy your skills manually. The main purpose of this feature is to make the process of farming rank in events less of a hassle.": "Fitur ini tidak dibuat secara ajaib. Jika Anda ingin melatih uma untuk TT atau CM, maka Anda harus membeli keterampilan Anda secara manual. Tujuan utama dari fitur ini adalah untuk membuat proses farming rank dalam acara menjadi lebih mudah.",
    Trainee: "Trainee",
    "Warning: Enabling this will override all other racing settings and they will be ignored.": "Peringatan: Mengaktifkan ini akan mengesampingkan semua pengaturan balapan lainnya dan pengaturan tersebut akan diabaikan.",
    "Warning: Enabling this will override all other racing settings and they will be ignored": "Peringatan: Mengaktifkan ini akan mengesampingkan semua pengaturan balapan lainnya dan pengaturan tersebut akan diabaikan.",
    "When enabled, allows setting different race strategies for each track distance.": "Jika aktif, memungkinkan pengaturan strategi balapan yang berbeda untuk setiap jarak lintasan.",
    "When enabled, the bot will attempt to retry a failed mandatory race only if the daily free race retry is available.": "Jika aktif, bot akan mencoba mengulang balapan wajib yang gagal hanya jika upaya ulang balapan gratis harian tersedia.",
    "When enabled, the bot will automatically stop when it encounters a mandatory race, allowing you to manually handle them.": "Jika aktif, bot akan otomatis berhenti ketika menemui balapan wajib, memungkinkan Anda untuk menanganinya secara manual.",
    "When enabled, the bot will ignore the warning popup about consecutive races and continue racing.": "Jika aktif, bot akan mengabaikan popup peringatan tentang balapan beruntun dan melanjutkan balapan.",
    "When enabled, the bot will load your selected in-game race agenda instead of using the racing plan settings. Note that this will disable the farming fans and racing plan settings.": "Jika aktif, bot akan memuat agenda balapan dalam game pilihan Anda alih-alih menggunakan pengaturan rencana balapan. Perhatikan bahwa ini akan menonaktifkan pencarian penggemar dan pengaturan rencana balapan.",
    "When enabled, the bot will not retry mandatory races if they fail and will stop.": "Jika aktif, bot tidak akan mengulang balapan wajib jika gagal dan akan berhenti.",
    "When enabled, the bot will override the racing behavior of any scenario such that it will not run any extra races except for the ones scheduled by the selected user's in-game racing agenda.": "Jika aktif, bot akan mengesampingkan perilaku balapan skenario apa pun sehingga tidak akan menjalankan balapan ekstra apa pun kecuali yang dijadwalkan oleh agenda balapan dalam game pengguna terpilih.",
    "When enabled, the bot will perform scheduled races from the in-game racing agenda during Summer instead of prioritizing Summer training. Note that this requires 'Enable User In-Game Race Agenda' to be enabled.": "Jika aktif, bot akan melakukan balapan terjadwal dari agenda balapan dalam game selama Musim Panas alih-alih memprioritaskan pelatihan Musim Panas. Perhatikan bahwa ini memerlukan pengaktifan 'Aktifkan Agenda Balapan In-Game Pengguna'.",
    "When enabled, the bot will proceed to the career completion screen when a mandatory race fails and retries are exhausted.": "Jika aktif, bot akan melanjutkan ke layar penyelesaian karir ketika balapan wajib gagal dan upaya ulang telah habis.",
    "When enabled, the bot will skip all training, rest, and mood recovery activities and focus exclusively on racing every day.": "Jika aktif, bot akan melewati semua aktivitas pelatihan, istirahat, dan pemulihan suasana hati dan fokus secara eksklusif pada balapan setiap hari.",
    "When enabled, the bot will start running extra races to gain fans.": "Jika aktif, bot akan mulai menjalankan balapan ekstra untuk mendapatkan penggemar.",
}

export function useTranslation() {
    const { misc } = useContext(GeneralMiscContext) || {}
    const lang = misc?.language || "en"

    return useCallback(
        (key: string) => {
            if (lang === "id") {
                return TRANSLATIONS[key] || key
            }
            return key
        },
        [lang]
    )
}
