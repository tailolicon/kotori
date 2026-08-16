# Kho extension Kotori

Kho nguồn riêng của Kotori, cùng kiểu Keiyoushi. Dán URL này vào **Duyệt → Cửa hàng tiện ích**:

```
https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo/index.pb
```

Bản tương thích Mihon cũ (nếu fork chỉ đọc JSON):

```
https://raw.githubusercontent.com/tailolicon/kotori/main/extensions/repo/index.min.json
```

Hiện có:

| Extension | Gói | Ghi chú |
|-----------|-----|---------|
| Hitomi.la | `app.kotori.extension.all.hitomi` | Popular / Latest / tag / thể loại, phân trang nozomi đúng Range |

## Thêm nguồn mới

1. Tạo module `extensions/<id>` giống `hitomi`.
2. Đăng ký trong `settings.gradle.kts`.
3. Chạy `.\extensions\publish-repo.ps1`.
4. Commit thư mục `extensions/repo/` và push `main`.

## Build

```powershell
.\extensions\publish-repo.ps1
```

APK ký bằng `extensions/keystore/kotori-extensions.jks`. Vân tay SHA-256 nằm ở `extensions/keystore/cert.sha256` — Kotori dùng vân tay này để tin các APK trong kho.
