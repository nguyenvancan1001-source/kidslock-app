KIDS LOCK — AI AGE MODEL / MÔ HÌNH AI ƯỚC LƯỢNG TUỔI

Put two files in this folder to switch the app to "advanced age model" mode:
Đặt 2 file vào thư mục này để app chuyển sang chế độ "mô hình nâng cao":

  age_model.tflite   – TensorFlow Lite / LiteRT model, input = 1 face crop (RGB, square)
  age_model.json     – describes the model, example:

  {
    "input_size": 224,
    "mean": 127.5, "std": 127.5,
    "bgr": false,
    "output": "regression",
    "scale": 1.0,
    "crop_margin": 0.25
  }

  For class-based models use "output": "buckets" and give one representative age per class:
  "bucket_ages": [2, 6, 10, 15, 20, 28, 38, 48, 60, 75]

Without these files the app uses BASIC mode, which must be calibrated with the family's
own faces in Settings before it can lock.
Không có 2 file này, app dùng chế độ CƠ BẢN và cần hiệu chỉnh bằng khuôn mặt người trong
gia đình (trong Cài đặt) trước khi có thể khoá.

LICENSE WARNING / LƯU Ý BẢN QUYỀN:
Many public age models are trained on datasets that FORBID commercial use (e.g. UTKFace,
IMDB-WIKI, Adience). For a paid app, use a model whose weights AND training data allow
commercial use, or buy/license one from a vendor.
Nhiều mô hình tuổi công khai được huấn luyện trên bộ dữ liệu CẤM dùng thương mại. Bán app
có phí thì phải dùng mô hình mà cả trọng số lẫn dữ liệu huấn luyện cho phép thương mại,
hoặc mua bản quyền từ nhà cung cấp.
