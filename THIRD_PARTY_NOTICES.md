# Сторонние компоненты и лицензии

В репозиторий входят не только собственные исходники — здесь перечислено всё
стороннее, с точными ревизиями. Это одновременно и соблюдение условий лицензий,
и указание того, откуда взят каждый бинарный файл.

## 1. Движок Rapfi — GPL-3.0-or-later

- **Что это:** `app/src/main/jniLibs/arm64-v8a/librapfi.so` — движок
  Гомоку/рендзю, собранный из исходников Rapfi.
- **Авторы:** Rapfi developers, © 2022.
- **Лицензия:** GNU General Public License, версия 3 **или любая более поздняя**
  (так указано в заголовках исходников Rapfi: *«either version 3 of the License,
  or (at your option) any later version»*). Полный текст — в файле
  [`LICENSE`](LICENSE).
- **Исходный код:** <https://github.com/dotb1ue/rapfi> (он же `dhbloo/rapfi`),
  коммит **`1387d11312190ec66665d361ff07a7c70221b78a`** (2026-10-23).
- **Внесённые изменения:** в `Rapfi/CMakeLists.txt` отключена линковка `-lpthread`
  при сборке под Android — bionic libc уже содержит pthread, и компоновка падала с
  `ld.lld: error: unable to find library -lpthread`. Изменение воспроизводится
  скриптом [`tools/patch_rapfi.ps1`](tools/patch_rapfi.ps1) и не меняет поведение
  движка. Помимо этого патча исходники не изменялись.
- **Как получить соответствующую сборку:** [`tools/build_rapfi.cmd`](tools/build_rapfi.cmd)
  (Windows) или [`tools/build_rapfi.sh`](tools/build_rapfi.sh) (Linux/WSL2) —
  клонируют указанный коммит, применяют патч, кросс-компилируют под
  Android arm64-v8a через Android NDK и кладут результат в `jniLibs/`.

## 2. Веса Rapfi (NNUE и классические) — CC0-1.0

- **Что это:** `app/src/main/assets/engine/config.toml`,
  `assets/engine/classical/*.bin`, `assets/engine/mix9svq/*.bin.lz4`.
- **Источник:** <https://github.com/dhbloo/rapfi-networks>, коммит
  **`7eeb19b55219a48138756293f92211a1539bab4d`**.
- **Лицензия:** CC0 1.0 Universal (public domain dedication) — в README репозитория
  сказано: *«All weight files are released freely under the CC0 license»*.
  Ограничений на использование, изменение и распространение нет.
- **Примечание:** `config.toml` — пример конфигурации оттуда же, с исправленными
  путями к файлам моделей (подкаталоги `classical/` и `mix9svq/`).

## 3. Библиотеки Android

Все распространяются под Apache License 2.0, совместимой с GPLv3:

| Библиотека | Лицензия |
|---|---|
| AndroidX Core, AppCompat, Activity, Lifecycle, ConstraintLayout | Apache-2.0 |
| Google Material Components for Android | Apache-2.0 |
| Kotlin Standard Library, kotlinx.coroutines | Apache-2.0 |

Полные тексты лицензий — в соответствующих артефактах на
[Maven Central](https://search.maven.org/) и
[Google Maven](https://maven.google.com/).

## 4. Лицензия самого приложения

Код приложения («Гомоку») распространяется под **GPL-3.0-or-later** — см.
[`LICENSE`](LICENSE). Выбор обусловлен пунктом 1: приложение поставляет бинарник
Rapfi, поэтому вся поставка должна соответствовать GPLv3, а копилефт гарантирует,
что приложение останется открытым и бесплатным.

### Что это значит для вас

- Вы можете свободно использовать, изменять и распространять приложение.
- При распространении сборки вы обязаны сохранить текст лицензии и обеспечить
  доступ к соответствующему исходному коду (этот репозиторий + ревизии из
  пунктов 1 и 2 полностью закрывают требование).
- Вы не можете добавлять дополнительные ограничения (например, запрет на
  коммерческое использование) на код под GPLv3.
