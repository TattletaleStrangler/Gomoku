# Движок Rapfi — размещение файлов (Android 10+)

Android 10+ (API 29) запрещает выполнять программы из домашнего каталога
приложения (`filesDir`, метка SELinux `app_data_file`). Единственное место,
где `exec()` разрешён — `nativeLibraryDir`. Поэтому файлы движка разделены:

```
jniLibs/arm64-v8a/
└── librapfi.so          ← исполняемый бинарник (Rapfi, переименован)

assets/engine/
├── config.toml           ← конфиг Rapfi
├── classical/            ← классические веса
└── mix9svq/              ← NNUE-веса
```

При запуске `NativeExe`:

1. **Бинарник** уже лежит в `nativeLibraryDir` (туда его распаковала ОС из
   `jniLibs/` APK'а) — можно сразу выполнять.
2. **Данные** (config + веса) копируются рекурсивно из `assets/engine/` в
   `filesDir/engine/`, который и становится рабочим каталогом процесса —
   Rapfi находит конфиг и веса рядом с собой.

## Сборка бинарника

Скрипты (Linux/WSL2 — `tools/build_rapfi.sh`, Windows 10 — `tools/build_rapfi.cmd`)
делают три вещи:

1. Кросс-компилируют Rapfi через CMake + NDK (`arm64-v8a`, `android-26`,
   `c++_static`, `-DUSE_NEON=ON`).
2. Копируют выходной `pbrain-rapfi` в `jniLibs/arm64-v8a/librapfi.so`.
3. Копируют `config.toml` и папки `classical/` / `mix9svq/` из подмодуля
   `Networks` репозитория [dotb1ue/rapfi](https://github.com/dotb1ue/rapfi)
   в `assets/engine/`.

После работы скрипта обязательно **пересоберите APK** — иначе изменения не
попадут в пакет.

## Что происходит при отсутствии бинарника

Если `librapfi.so` нет в `jniLibs/` (как в этом репозитории по умолчанию),
приложение использует встроенный AI (альфа-бета + эвристика), без падений.