# PayMap

App Android untuk rekod bayaran **MAE** dan **TnG eWallet** berserta **lokasi di peta**.

## Ciri
- Selepas bayar (scan QR / DuitNow / tap), pop up keluar dengan jumlah, nama kedai dan lokasi. Semak, edit, tekan **Simpan**.
- Lampir resit (kamera, gambar atau PDF).
- Akaun **Personal / Business**.
- **Bajet bulanan** ikut kategori, dengan amaran 80% dan terlebih.
- Peta **"Ke mana duit pergi"** (OpenStreetMap, percuma).
- **Penyata automatik setiap 1hb** — lihat, simpan ke Download/PayMap, atau kongsi (PDF).
- Semua data disimpan dalam telefon sahaja.

## Pasang
1. Muat turun `PayMap.apk` dari tab **Releases**.
2. Pasang, buka, benarkan lokasi & pemberitahuan.
3. Tetapan → hidupkan **Baca notifikasi MAE & TnG**.
   Jika keluar "Tetapan terhad": Info App PayMap → ⋮ → **Benarkan tetapan terhad**, kemudian cuba semula.
4. Tetapan → **Hantar bayaran uji** untuk cuba pop up.

## Bina
APK dibina automatik oleh GitHub Actions setiap kali kod dikemas kini (lihat `.github/workflows/build.yml`).
