# Bàn giao: chu trình nâng cấp render dịch (r95)

Tài liệu này đủ để một agent bất kỳ (Claude khác, GPT, Gemini, người thật) tiếp tục nâng
chất lượng render dịch **đúng chu trình** đang dùng, không cần lịch sử hội thoại. Đọc xong
file này + `AGENTS.md` + `regression/README.md` là đủ bối cảnh.

## Chu trình bắt buộc (đã cứu dự án ba lần — không được bỏ bước)

1. **Trước khi sửa bất kỳ gì trong `app/src/main/java/mihon/feature/translation/`:**
   `python regression/run.py` phải ra **ALL CLEAR**. Không ALL CLEAR = có thứ hỏng sẵn,
   dừng lại tìm nguyên nhân trước.
2. Sửa lỗi. Trang phơi ra lỗi **phải** vào `regression/corpus/` (JPEG q68–80, tên
   `<bộ>-<8 ký tự sha1>.jpg`, khai trong `regression/manifest.json` kèm mô tả lỗi nó gác).
3. Chạy lại `run.py`. **Soi từng khác biệt bằng mắt** (ảnh so sánh tự sinh trong
   `regression/report/`): trang mới đổi = đúng ý; trang cũ đổi = hồi quy cho tới khi
   chứng minh ngược lại. Trace diff (`.trace.diff`) thường chỉ thẳng guard nào đổi hành vi.
4. Duyệt xong hết: `python regression/run.py --bless`, rồi commit **corpus + golden + code
   cùng một commit**.
5. Đổi hành vi render = bump `RENDERER_VERSION` trong `TranslationPreferences.kt`
   (hiện `r95`) để cache người dùng được render lại.
6. Suite xanh **chỉ chứng minh không gì đổi** — không chấm chất lượng. Muốn đo chất lượng:
   chạy trọn chương thật qua harness rồi soi từng dải (mục "Đo chất lượng" dưới).

## Đo chất lượng trên chương thật (cách phát hiện 85/227 dải lỗi ban đầu)

1. Đẩy toàn bộ trang gốc một chương vào máy:
   `adb -s <serial> push <trang>.webp /storage/emulated/0/Android/data/app.mihon.dev/files/regression/in/`
2. Mở app (`monkey -p app.mihon.dev -c android.intent.category.LAUNCHER 1`), rồi
   `am broadcast -n app.mihon.dev/mihon.feature.translation.debug.RegressionReceiver`.
   Chờ `out/DONE.txt` (poll theo tiến độ file, đừng đặt deadline cứng — máy ảo nhanh chậm thất thường).
3. Kéo `out/` về; output trùng tên input nên ghép cặp 1:1. Sinh ảnh dải so sánh
   (mẫu code trong `.transwork/pair.py` nếu còn; logic: diff >30/kênh, gộp hàng cách >260px
   thành dải, cắt kèm lề 60px, ghép gốc|dịch cạnh nhau).
4. Duyệt từng dải bằng mắt (chia cho nhiều agent con nếu có). Phân loại lỗi RENDER
   (ghost, đè chữ, vá lệch màu, tràn bubble, xoá mất tranh, stamp lạc, sót bubble, mất màu
   chữ) — **không** tính lỗi câu chữ của provider.
5. Provider trong harness là `DeterministicTranslationProvider` (debug-only): cùng input ra
   cùng output nên so được từng byte; nó đã **echo SFX ngắn** (≤5 ký tự một từ) như Google
   thật để guard `isUntranslated` bỏ qua — đừng "sửa" điều đó, nó giữ cho số đo trung thực.

## Trạng thái hiện tại (2026-08-15, sau 3 đợt sửa r93→r95)

Commit mốc: `f1c290b` (ghost gate + 3 guard), `bf6168e` (tô nền theo hàng cho gradient),
`1c05f7a` (màu mực theo cụm + kẹp vùng tô + nội suy hàng + guard stamp lạc mở rộng).
Corpus 47 trang / 4 bộ (`asg8`, `ch35` đen trắng; `kd53`, `asg123` màu — 15 trang màu thêm
2026-08-15). Audit trước sửa: 85/227 dải lỗi; sau wave 1–2 đo được ~30% trên phần tư đã duyệt;
sau wave 3 (r95) đã chạy lại trọn 50 trang, **321 dải trong `.transwork/live2/sheet/` chưa được
duyệt hết** (4 agent duyệt bị đứt giữa chừng vì session limit) — việc dang dở số 1.

Cơ chế chính đã có trong `render/BubbleRenderer.kt` + `render/BubbleFill.kt`:
- `ensureErased`/`ghostSurvivors` — cổng kiểm sau xoá, inpaint phần sót, từ chối dán nếu vẫn sót
- `eraseUniformCaptionStrip` + kiểm tra interior đồng nhất; `paintCaptionGlyphIslands` tô theo đảo
- `anchorSlotToText`, `looksLikeTextureMisread`, kẹp flood của block tổng hợp quanh dòng OCR
- `paintRegionWithBackground` — tô theo hàng khi spread >45, nội suy tuyến tính giữa hàng đo được
- `BubbleFill.sampleInk` — màu mực = cụm quanh cực trị, ưu tiên trong ô dòng OCR

## Lỗi còn lại đã biết (xếp theo độ nặng)

1. **Gộp hai bubble thành một cột chữ** — `PageTranslator.kt` (coalesce/realign): asg trang
   `2eb828…` "catastrophic", trang `a9a108…`/`d39698…` chữ rơi giữa hai bubble. Chưa ai động vào.
2. Trang credit kd `b5f6f0…`: còn tô đen + ghost pills (kiểm tra lại sau r95 — clip flood có thể
   đã đỡ, chưa xác nhận).
3. Ghost đầu/cuối dòng trên chữ lớn (kd `f3c0b4…`): pad của ghost gate (`GHOST_LINE_PAD_X=0.35`)
   có thể chưa với tới đuôi chữ SFX cao.
4. Banding nhẹ có thể còn trên hộp gradient hướng tâm (mô hình per-row là 1D) — đã đỡ nhiều nhờ
   nội suy, cần xác nhận trên live2.
5. Manga Nhật chưa test được: nguồn tiếng Anh sạch duy nhất là MANGA Plus — **chưa cài extension**.

## Bẫy môi trường (mỗi cái từng làm mất nhiều giờ)

- Build bằng **PowerShell**, không phải Bash (`gradlew.bat` not recognized dưới Bash).
  Đặt `TMP/TEMP=C:\Windows\Temp` trước khi build. Pipe output gradle qua
  `Select-Object -First N` có thể giết client (exit 255 không lời nhắn) — ghi ra file log.
- Ổ **E: rất hay đầy** → `packageRelease` fail "not enough space". Dọn: `.transwork` dir cũ,
  `app/build/outputs/apk/*`, gradle build-cache.
- MuMu: serial mặc định `127.0.0.1:7555`; **sau khi VM chết và relaunch bằng
  `MuMuManager.exe api -v 0 launch_player`, port đổi thành `16384`** → truyền `--serial` cho run.py.
- Play Services có thể đơ toàn máy → pipeline treo chờ ML Kit vô hạn (thread dump thấy toàn
  worker parked). Force-stop `com.google.android.gms` hoặc relaunch VM.
- **Không bao giờ** viết code per-pixel có cấp phát (boxing) hoặc O(vùng × số dòng) trong
  renderer: strip 900×16000 sẽ biến nó thành treo. Duyệt theo ô dòng làm domain, median bằng
  histogram 256 bin (đã có ví dụ trong code).
- `Bitmap.createBitmap(src,0,0,w,h)` trả về chính source khi kích thước bằng nhau — recycle nó
  là chết trang. `.gitattributes` phải giữ `*.onnx binary`.
- Golden `.png` chỉ nằm cục bộ (~200MB, gitignore); máy mới phải `--bless` một lần trên checkout
  sạch trước khi bắt đầu sửa, còn `PIXELS.sha256` trong git vẫn bắt được mọi thay đổi.

## Việc dang dở cụ thể

1. **Release v1.0.8** để người dùng cập nhật trực tiếp: APK release đã build xong trong
   `app/build/outputs/apk/release/` (versionName 1.0.8, đã ký). Còn lại: chạy
   `.transwork/package-release.ps1` (đổi tên 5 APK + sinh `update.json` đúng schema v1.0.6 —
   updater trong app đọc asset `update.json` của release GitHub mới nhất), commit bump version,
   tag `v1.0.8`, `gh release create v1.0.8 <5 apk> update.json --title "Kotori v1.0.8" --notes <vi>`.
   Smoke-test bản release trên MuMu trước khi publish (R8 từng giết ML Kit/ONNX).
2. **Duyệt 321 dải trong `.transwork/live2/sheet/`** → tally lỗi sau r95, trang lỗi mới → corpus.
3. Sửa mục 1 (coalesce) trong "Lỗi còn lại" — nặng nhất, chưa ai làm.
4. 50 trang input gốc của hai chương test nằm ở
   `/storage/emulated/0/Android/data/app.mihon.dev/files/regression/in/` trên máy ảo —
   **kéo về backup trước khi uninstall app** (bản host đã bị dọn vì đầy đĩa).

## Lệnh giao cho agent mới

> Đọc `HANDOFF-translation-render.md`, `AGENTS.md`, `regression/README.md` trong repo
> `E:\Project\kotori`. Làm tiếp "Việc dang dở" theo đúng "Chu trình bắt buộc". Không sửa
> pipeline khi suite chưa ALL CLEAR; không bless khi chưa soi từng diff bằng mắt.
