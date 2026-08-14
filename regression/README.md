# Translation regression suite

Mục đích: **mỗi lỗi dịch đã sửa trở thành một bài kiểm tra vĩnh viễn.** Trước đây, sửa một lỗi
render đã ba lần làm hỏng chỗ đang chạy tốt (bộ lọc logo bắt nhầm thoại mực tối `#0c1924`; lỗi
ghost phải sửa ba lần vì hai lần đầu sai tầng; bộ lọc echo loại nhầm bubble hợp lệ) — và chỉ bị
phát hiện qua ảnh chụp màn hình của người dùng nhiều ngày sau. Bộ này phát hiện điều đó trong vài
phút, ngay trên bàn làm việc.

## Cách hoạt động

- `corpus/` — các trang thật đã từng phơi ra lỗi (manifest.json ghi mỗi bộ gác lỗi nào).
- Harness debug (`app/src/debug/.../RegressionReceiver.kt`) dịch từng trang bằng
  `DeterministicTranslationProvider`: không mạng, không quota, và cùng một input luôn cho cùng một
  output — nhờ đó ảnh render so sánh được từng byte giữa hai lần chạy.
- `golden/` — kết quả đã duyệt, ba lớp:
  - `.trace.txt` (commit) — các dòng log quyết định của pipeline: guard nào giữ/loại, vì sao.
    Diff lớp này thường tự chỉ ra thủ phạm.
  - `PIXELS.sha256` (commit) — chữ ký từng ảnh render; phát hiện mọi thay đổi pixel kể cả trên
    checkout mới.
  - `.png` (chỉ cục bộ, ~200 MB — gitignore) — để vẽ ảnh so sánh golden-vs-now. Máy mới muốn có
    lớp này thì bless một lần trên checkout sạch trước khi bắt đầu sửa.
- `run.py` đẩy corpus lên máy, chạy, kéo về, so với golden. Khác pixel → xuất ảnh so sánh
  golden-vs-now vào `report/`; khác trace → diff văn bản chỉ thẳng guard đổi hành vi.
  Chờ theo *tiến độ* (kẹt 6 phút không ra file mới thì báo), không theo tổng thời gian — máy ảo
  nhanh chậm thất thường vài lần giữa các lượt. `--compare-only` bỏ qua bước chạy máy và chỉ so
  kết quả đã có trong `out/` (dùng khi lượt chạy trước đã xong nhưng bạn muốn so lại).

## Quy trình bắt buộc khi sửa pipeline dịch

1. **Trước khi sửa**: `python regression/run.py` — phải ALL CLEAR. Nếu không, dừng lại: có thứ đã
   hỏng từ trước, sửa chồng lên chỉ giấu nó đi.
2. Sửa lỗi. Trang phơi ra lỗi đó **phải được thêm vào `corpus/`** (nén JPEG q68–q80, đặt tên
   `<bộ>-<8 ký tự sha1>.jpg`, ghi vào `manifest.json` lỗi nó gác).
3. Chạy lại `run.py`. Đọc từng khác biệt:
   - Trang vừa thêm đổi là **đúng ý** (đó là bản sửa).
   - Bất kỳ trang cũ nào đổi là **hồi quy cho tới khi chứng minh ngược lại** — mở ảnh trong
     `report/`, nhìn bằng mắt, và chỉ chấp nhận khi giải thích được vì sao thay đổi là cải thiện.
4. Khi mọi khác biệt đã được duyệt bằng mắt: `python regression/run.py --bless`, xem
   `git diff --stat regression/golden`, commit corpus + golden **cùng commit** với bản sửa.
5. Bump `RENDERER_VERSION` như thường lệ khi hành vi render đổi.

## Giới hạn đã biết

- Chạy trên MuMu (`--serial 127.0.0.1:7555` mặc định). Golden gắn với thiết bị+model ML Kit; đổi
  máy hoặc Play Services cập nhật model OCR có thể làm trace lệch hàng loạt — khi đó đọc diff:
  nếu chỉ các dòng OCR đổi đồng loạt, đó là trôi model, duyệt và bless lại.
- Đường ghép strip (`translateStrip`/`translatePageRun`) chưa có harness trên máy; hình học
  ghép–cắt được kiểm bằng port Python trong phiên phát triển. Việc kế tiếp: thêm chế độ chạy
  theo cụm vào receiver.
- Provider thật (Gemini/Google/Offline) không nằm trong phạm vi bộ này — nó kiểm **pipeline
  render**, không kiểm chất lượng câu chữ.
