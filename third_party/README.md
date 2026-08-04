# Third-party native sources

## llama.cpp (offline translation) — pin **b10240**

- Upstream: https://github.com/ggml-org/llama.cpp
- License: MIT (`llama.cpp-LICENSE.txt` / `app/src/main/assets/licenses/llama.cpp-LICENSE.txt`)
- Expected path: `third_party/llama.cpp/` (with its root `CMakeLists.txt`)
- CMake in `app/src/main/cpp/CMakeLists.txt` **requires** this tree; no network FetchContent.

The release tree is vendored from the official `b10240` source archive so local and CI builds do
not depend on the network. The archive used for this pin has SHA-256:

`E4760AC087F1B23DD10E477C2F12EF0C7D867F21C89BC5B4AE1A580314489317`

To reproduce the vendor tree:

```
curl -L -o llama.cpp-b10240.tar.gz https://github.com/ggml-org/llama.cpp/archive/refs/tags/b10240.tar.gz
```

Do **not** commit GGUF weights or Windows llama.cpp binaries.

## HY-MT model (not vendored)

User-initiated download inside the app. License assets under `app/src/main/assets/licenses/`.
