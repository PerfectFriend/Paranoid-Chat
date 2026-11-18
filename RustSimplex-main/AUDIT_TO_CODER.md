# Технический аудит и ТЗ на доработку `RustSimplex` (`smp-rs`) для интеграции в Android-клиент **Paranoid**

**Ревизия аудита:** `smp-rs` v0.2.0 (состояние по v0.71 / `docs/AUDIT-REPORT.md`)  
**Цель интеграции:** использовать `smp-rs` через FFI (`cdylib` / `uniffi`) как основное сетевое, транспортное и криптографическое ядро Android-приложения **Paranoid** (Jetpack Compose frontend остаётся на Kotlin).

---

## Краткий вердикт

Сильная база уже построена:
- wire-совместимый слой **SMP (v17–v23)**, строгий транспортный `sb_chain`, `PinVerifier` против MITM, fail-closed encrypted `store`, `proxy` с SSRF-защитой и panic-free `server.rs` (`0` блоков `unsafe`);
- рабочий **live interop** против публичных релеев `smp11.simplex.im:5223` и `xftp1.simplex.im:443`.

Однако в текущем состоянии подключать `smp-rs` в мобильный продакшен нельзя — ниже перечислены **4 критических блокера** и список **обязательного security/reliability hardening** с точными точками в коде.

---

## 1. [БЛОКЕР №1] Починить `SMP_E2E=1` в `chat` / `pqdr` и включить E2E по умолчанию

### Симптом
По умолчанию `agent.rs` и `chat.rs` работают **без E2E между собеседниками** (только router-level `crypto_box`). При запуске с `SMP_E2E=1` `chat`-тест падает с `ERR AUTH` / `"no receiving chain"` (зафиксировано в `docs/AUDIT-REPORT.md` v0.66–v0.71).

### Точная причина бага
1. В `tests/chat.rs` инициатор (`a`) вызывает `accept_contact()`, а присоединившаяся сторона (`b`) вызывает `await_hello()`.
2. Сразу после этого **обе стороны вызывают `send_info(...)` до чтения входящих** (`tests/chat.rs:24–26`), то есть `b` ещё не прочитал первое сообщение от `a`.
3. Когда `b` вызывает `send_info(&mut b, "Bob")` → `PqdrRatchet::encrypt()`, срабатывает ветка `src/pqdr.rs:258`:
   ```rust
   if self.pending.is_none() && self.ckr.is_none() {
       self.dh_s = x25519_keypair();
       self.kem_s = kem_keypair();
       ...
   ```
   **`b` генерирует новые `self.dh_s` и `self.kem_s`, перезаписывая и уничтожая исходные `kem_s.dk` и `dh_s`**, которые были переданы в `ConnInfo` и на которые инициатор (`a`) уже инкапсулировал первое сообщение (`KemParams::CtPk`).
4. Когда `b` затем читает сообщение от `a` (`recv_text`), `kem_decaps(&self.kem_s.dk, ct)` использует уже перезаписанный ключ → мусорный `kem_ss` → `ERR AUTH`. Одновременно `a` не сможет корректно расшифровать встречное `x.info` от `b`.

### Что нужно сделать
- Разделить в `PqdrRatchet` начальную пару приёма (`init_dh_s`, `init_kem_s`, объявленную в `ConnInfo`) и активный отправляющий ключ при `receiver-first` отправке (либо сохранять исходные `(init_dh_s, init_kem_s)` до первой успешной декапсуляции `CtPk` от пира). Одновременная отправка первых сообщений (`x.info` / `x.msg.new`) с обеих сторон до чтения входящих должна работать без рассинхронизации.
- Убрать гейт через переменную окружения `std::env::var("SMP_E2E")` (`src/agent.rs:425, 464`): E2E (PQDR) должен быть **включён по умолчанию** в `Agent`, а plaintext-режим (если нужен старым тестам) вынести в явный конфиг.
- Добавить тест в `tests/chat.rs`: одновременный `send_info` + `send_text` в обе стороны под обязательным PQDR, out-of-order доставка, повторная инициализация после сериализации/восстановления.

*(Замечание по рефакторингу: в `src/pqdr.rs:30–82` реализованы ручные `hmac_sha512`/`hkdf`, хотя в `Cargo.toml` уже подключён крейт `hkdf` и он используется в `src/sb_chain.rs` — лучше унифицировать на `hkdf::Hkdf<Sha512>`.)*

---

## 2. [БЛОКЕР №2] Убрать жёсткую зависимость библиотеки от `openssl` (Android NDK) и перенести логику `v71` в `lib`

### Проблема
- В `Cargo.toml` безусловно подключены `openssl = "0.10.82"` и `tokio-openssl = "0.6.5"`. Кросс-компиляция `openssl-sys` под Android NDK (`arm64-v8a`, `armeabi-v7a`, `x86_64`) заметно усложняет сборку и раздувает `.so`.
- Библиотечный транспорт (`src/transport.rs`, `src/client.rs`) написан на `rustls`, однако рабочий handshake для публичных релеев (`TLS Finished verify_data` в качестве `sessionId`, `ed448` в `signature_algorithms`, HTTP/2 XFTP v4) сейчас находится только в CLI-бинарниках `src/bin/smp_v71.rs` и `src/bin/xftp_h2_v71.rs`.

### Что нужно сделать
- Вынести `openssl`, `tokio-openssl` и CLI-бинарники `smp-v71` / `xftp-h2-v71` под optional Cargo feature (например, `features = ["v71-probes"]`), чтобы при сборке библиотечного крейта `smp-rs` для Android (`--no-default-features`) `openssl-sys` не компилировался вовсе.
- Перенести рабочий путь совместимости с публичными релеями SimpleX (и SMP v17–v23 с `TLS Finished` / channel binding, и HTTP/2 XFTP v4) в библиотечный клиент, не опираясь на системный OpenSSL (через абстракцию TLS-коннектора, `rustls` или `boring` при необходимости, но без `openssl-sys`).
- Убрать `SslVerifyMode::NONE` из XFTP-пути (в `src/bin/xftp_h2_v71.rs` сейчас проверка сертификата отключена) и подключить строгий `PinVerifier` по `keyHash`, как в `src/transport.rs`.

---

## 3. [БЛОКЕР №3] Async-движок клиента (`tokio`), `SOCKS5` (Tor) и сериализация состояния

### Проблема
- `Client` и `Agent` (`src/client.rs`, `src/transport.rs`) работают на блокирующем `std::net::TcpStream` с 30-секундным таймаутом и поллингом `thread::sleep(Duration::from_millis(1))` в `write_all_tol` / `read_exact_tol`.
- Один `Client` жёстко привязан к одному сокету и блокирующему вызову `recv_block()`, нет авто-переподключения, keep-alive и push-стриминга событий.
- `PqdrRatchet`, `Agent`, `X25519Keypair`, `Ed25519Keypair`, `KemKeypair` не реализуют сериализацию/десериализацию — при перезапуске мобильного приложения все контакты и состояния ратчета потеряются.
- В `src/transport.rs` отсутствует поддержка `SOCKS5`-прокси (в **Paranoid** есть настройка маршрутизации через Tor/Orbot и кастомный прокси).

### Что нужно сделать
1. **Асинхронный клиентский движок (`tokio` + `tokio-rustls`):**
   - Уйти от блокирующих `std::net` и `thread::sleep(1ms)` в клиентском транспорте.
   - Сделать управляемый `SmpClientEngine` с фоновым event-loop, авто-реконнектом, keep-alive `PING`, мультиплексированием очередей и асинхронным потоком событий (`mpsc`/`broadcast`) для входящих сообщений и квитанций доставки.
2. **Персистентность клиентского состояния:**
   - Добавить безопасную сериализацию/десериализацию состояния контактов и `PqdrRatchet` (включая `skipped` и `pending`) с шифрованием по аналогии с `SMP1` в `src/store.rs` (ключ передаётся из Android Keystore / KDF от PIN).
3. **Поддержка `SOCKS5`:**
   - Добавить опциональный `Socks5Config { host, port, auth }` перед TLS-хендшейком в `transport.rs` с обязательным удалённым DNS-резолвингом (`ATYP = 0x03`, domain name), чтобы DNS-запросы не уходили мимо Tor.
4. **Чистый FFI-фасад (`paranoid-ffi` / `uniffi`):**
   - Подготовить высокоуровневый API без привязки к `std::env` и CLI (`create_contact`, `join_contact`, `send_message`, `ack_message`, `subscribe_events`, `xftp_upload`, `xftp_download`, `xftp_delete`, `set_proxy_mode`), готовый к генерации биндингов через `uniffi` (`crate-type = ["cdylib"]`).

---

## 4. [БЛОКЕР №4] Потоковый `XFTP` для больших файлов (`DC Cloud`, 16 GB+)

В **Paranoid** модуль `DC Cloud` предполагает работу с крупными контейнерами (от 16 GB и выше) и немедленное удаление доставленных данных и метаданных с сервера.

### Текущие ограничения `src/xftp.rs`
- `encrypt_file(data: &[u8])` и `upload_file(..., data: &[u8])` принимают весь файл в RAM (`Vec<u8>`), а `download_file` собирает все расшифрованные чанки в память целиком — на Android это гарантированный `OutOfMemoryError`.
- В `FileDesc` не хранится точный исходный размер файла (`orig_size`), поэтому при снятии zero-padding последний чанк неоднозначен (`src/xftp.rs:131–133`).
- `src/xftp.rs` работает поверх SMP-блоков вместо `HTTP/2` (`xftp/1`), как в живом `xftp1` (`src/bin/xftp_h2_v71.rs`).

### Что нужно сделать
- Перевести `XFTP` на **потоковую (streaming) обработку с диска** (`tokio::fs::File` / `Read` + `Write` / file descriptor) с поточным подсчётом `SHA-256` и буфером на 1–2 чанка в памяти.
- Добавить `orig_size: u64` в `FileDesc` для побайтово точной обрезки при расшифровке.
- Добавить callback прогресса (`bytes_done`, `total_bytes`) и поддержку `HTTP/2`-транспорта (`xftp/1`).
- Обеспечить надёжный метод удаления контейнера (`FDEL`) с очисткой локальных временных файлов и метаданных сразу после подтверждения доставки.

---

## 5. Безопасность, паники и чистка кода (Security & Hardening)

1. **Затирание секретов в памяти (`zeroize`):**
   - Подключить крейт `zeroize` (`Zeroize`, `ZeroizeOnDrop`) для `X25519Keypair`, `Ed25519Keypair`, `KemKeypair`, всех полей `PqdrRatchet` (`rk`, `cks`, `ckr`, `nhks`, `nhkr`, `skipped`, `pending`), ключей `sb_chain` и временных plaintext-буферов.
2. **Устранить оставшиеся `panic!` / `unwrap()` / `expect()` в библиотечных модулях:**
   - **`src/sb_chain.rs:75–76` (`unpad_block`):** читается `n = u16::from_be_bytes([buf[0], buf[1]]) as usize` и сразу делается срез `buf[2..2 + n].to_vec()` без проверки `2 + n <= buf.len()` — паника при некорректной длине после расшифровки. Заменить на `Result` с проверкой границ.
   - **`src/pqdr.rs:388` (`kem_encaps_pub`):** `.expect("kem pk len")` — в `init_sender` (`src/pqdr.rs:197`) длина `kem_r` заранее не проверяется, битый `ConnInfo` уронит процесс. Вернуть `Result`.
   - **`src/pqdr.rs:118–119`:** `.unwrap()` в `encode_msg_header`.
   - **`src/agent.rs:163, 172, 181`:** `.unwrap()` на `r.take(8)...try_into()`; строка `216`: `.expect("srv dh spki")`; строки `422, 455`: `.unwrap()` на `peer_snd_id`; строка `490`: `.expect("kem")`.
   - **`src/chat.rs:39`:** `.expect("json encode")`.
   - **`src/transport.rs:189, 192, 284`:** `.expect(...)` при конфигурации TLS.
3. **Не возвращать тихие нули при ошибке AEAD в `src/crypto.rs`:**
   - В `secretbox_encrypt` (`src/crypto.rs:158`) и `raw_secretbox_encrypt` (`src/crypto.rs:181`) сейчас используется `.unwrap_or_else(|_| vec![0u8; 16])` — ошибка шифрования маскируется нулевыми байтами. Перевести на `Result`.
4. **Убрать отладочный вывод и дамп трафика на диск в `src/client.rs`:**
   - `src/client.rs:53, 55`: `eprintln!("[dbg] server hello block: ...")` — перевести на `tracing` (без утечки сырого трафика и чувствительных метаданных в `logcat`).
   - `src/client.rs:159–169`: чтение `SMP_DUMP_RAW` и запись блоков в `temp_dir()/raw_blocks.hex` убрать из release-сборки или спрятать под `#[cfg(feature = "debug-dump")]`.
5. **Мелкая чистка и синхронизация документации:**
   - `src/agent.rs:75–78`: убрать мёртвые строки перед `o.clear()`.
   - `src/agent.rs:441–451` (`await_hello`): `init_ci` парсится дважды, первый результат никуда не идёт.
   - `src/chat.rs:64–90` (`recv_text`): убрать дублирование логики `Agent::recv_agent()`.
   - `docs/SECURITY.md`: актуализировать (сейчас в документе остались устаревшие утверждения о том, что `pqdr` не подключён и `--store` не шифруется).
6. **Совместимость E2E-конверта / защита заголовка:**
   - Сейчас в `pqdr.rs` поля `nhks`/`nhkr` вычисляются в `root_kdf`, но `MsgHeader` отправляется в открытом виде (также используется `XSalsa20-Poly1305` вместо `AES-256-GCM` и `X25519` вместо `Curve448`).
   - Если совместимость E2E с официальными клиентами Haskell `simplex-chat` не требуется (работаем только внутри экосистемы `Paranoid` / `smp-rs`), как минимум стоит задействовать уже вычисляемые `nhks`/`nhkr` для шифрования `MsgHeader`. Если же нужна полная совместимость с внешними клиентами SimpleX — привести `pqdr.rs` к wire-спецификации (`X448` + encrypted header + `AES-256-GCM`).

---

## Чек-лист приёмки (Definition of Done перед подключением к Android)

1. `cargo test -- --test-threads=1` проходит на 100% **с включённым PQDR E2E по умолчанию**, включая тест одновременного `send_info` + `send_text` с обеих сторон, out-of-order и восстановления ратчета из снапшота.
2. Библиотека собирается без `openssl-sys` (проходит `cargo check --no-default-features` и кросс-компиляция под `aarch64-linux-android` / `x86_64-linux-android`).
3. В библиотечных модулях (`src/`) нет паник/`unwrap`/`expect` на сетевых и wire-путях, секретные ключи очищаются через `zeroize`, отладочные дампы вырезаны.
4. Клиент полностью асинхронный (`tokio`), поддерживает `SOCKS5` (с удалённым DNS), умеет сохранять/восстанавливать состояние контактов и ратчетов.
5. `XFTP` работает потоково от файла к файлу без загрузки всего объёма в RAM, хранит `orig_size` и корректно удаляет пакеты через `FDEL`.
