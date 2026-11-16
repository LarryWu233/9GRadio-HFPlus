# AIS-catcher core

- Source: https://github.com/jvde-github/AIS-catcher
- Commit: `d5b1e0a50c68f81e8ef59a057899530bf365fc0a`
- Licence: GPL-3.0-or-later; original `LICENSE` and notices retained.
- Included: unchanged `Source/`, `cmake/` and root CMake build file. Canonical-LF file checksums are recorded in `SHA256SUMS`.
- Integration: headless executable with stdin CU8/CF32 input and JSON output. Upstream device drivers, web/API services, database and optional library backends are disabled by the parent adapter. Existing Android transport owns the receiver; no second USB opener.
- Algorithms and message parsing are upstream code. Parent CMake adapts packaging, source-path remapping and 16 KB link alignment. Android adapter/lifetime/presentation code lives outside this directory.
- Network outputs require explicit command-line configuration; the embedded command does not enable an external feed or server.
