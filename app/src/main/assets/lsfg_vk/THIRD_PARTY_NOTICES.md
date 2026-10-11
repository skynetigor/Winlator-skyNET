# Third-party notices: LSFG frame-generation layer

`liblsfg-vk-layer.so` is the prebuilt Android arm64-v8a build of **lsfg-vk-android**
release `v1.0.4-android` (asset `liblsfg-vk-arm64-v8a.so`, SHA-256
`6eecff3731c33c5d90f2a658acda2e69912159448ee8afa61db6176361ed5e4f`).

- Source: https://github.com/GameNative/lsfg-vk-android/releases/tag/v1.0.4-android
- Derived from lsfg-vk v1.0.0 by PancakeTAS (https://github.com/PancakeTAS/lsfg-vk),
  with Android patches by FrankBarretta, xXJSONDeruloXx and the GameNative contributors.

The layer loads compute shaders at runtime from the user's own `Lossless.dll`
(Lossless Scaling, Steam app 993090). That DLL is proprietary and is **not**
distributed with this application; users supply their own copy.

The layer statically links the libraries below. Their licence texts follow.

---

## lsfg-vk / lsfg-vk-android (MIT)

Source: https://github.com/GameNative/lsfg-vk-android

```
## MIT License

Copyright (c) 2025 lsfg-vk

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## pe-parse (MIT)

Source: https://github.com/trailofbits/pe-parse

```
The MIT License (MIT)

Copyright (c) 2013 Andrew Ruef

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

---

## dxbc — DXVK's DXBC compiler (zlib)

Source: https://github.com/PancakeTAS/dxbc

```
                  Copyright (c) 2017 Philip Rebohle
                  Copyright (c) 2019 Joshua Ashton
                  Copyright (c) 2019 Robin Kertels
                  Copyright (c) 2023 Jeffrey Ellison

                          zlib/libpng license

This software is provided 'as-is', without any express or implied
warranty. In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

– The origin of this software must not be misrepresented; you must not
  claim that you wrote the original software. If you use this software
  in a product, an acknowledgment in the product documentation would be
  appreciated but is not required.

– Altered source versions must be plainly marked as such, and must not
  be misrepresented as being the original software.

– This notice may not be removed or altered from any source distribution.
```

---

## toml11 (MIT)

Source: https://github.com/ToruNiina/toml11

```
The MIT License (MIT)

Copyright (c) 2017 Toru Niina

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

---

## volk (MIT)

Source: https://github.com/zeux/volk

```
Copyright (c) 2018-2026 Arseny Kapoulkine

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

# Linux containers: `lsfg_vk/linux-arm64/liblsfg-vk.so`

The same lsfg-vk-android source (tag `v1.0.4-android`, commit
`ea1e3f9cf1196cfda7d75fadfdd51616b388e021`) built for **aarch64 glibc**, the Arch Linux ARM runtime of the Linux
containers, instead of the Android toolchain. Cross compiled with zig 0.17.0 (`aarch64-linux-gnu.2.35`, libc++ linked
in statically), cmake 4.4.4, Vulkan-Headers 1.3.275, stripped. Recipe: `tools/lsfg-linux/build.sh`.
SHA-256 of the shipped file: `0fedd60653c105efe8bff97302eba8933ec8fded6af0f8795968cfa64771f9d9`.

Modification: the sources are built with the fork's Android code path (frame generation on the game's own device
and queue, synchronised with a wait-idle), because Turnip on KGSL cannot export the OPAQUE_FD semaphores the
stock desktop path shares between two devices. That code sits under `#ifdef __ANDROID__`; `build.sh` renames the
macro to `LSFGVK_SINGLE_DEVICE` in the source tree and supplies `tools/lsfg-linux/shim` for the two Android
headers (`android/log.h`, `android/hardware_buffer.h`) the code includes. No other change to the source.

Submodules at the commits the tag pins: dxbc `78ab59a8aaeb43cd1b0a5e91ba86722433a10b78`, pe-parse
`31ac5966503689d5693cd9fb520bd525a8710e17`, toml11 `be08ba2be2a964edcdb3d3e3ea8d100abc26f286`, volk
`be3dbd49bf77052665e96b6c7484af855e7e5f67`. The licences listed above apply to it unchanged.

It has been checked as an ELF only (AArch64, needs `libc.so.6` only, exports `layer_vkGetInstanceProcAddr` and
`layer_vkGetDeviceProcAddr`, GLIBC symbols up to 2.34) and was **not run on a device** when it was added.
