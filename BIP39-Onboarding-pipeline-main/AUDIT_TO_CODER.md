# Техническое письмо разработчику `BIP39-Onboarding-pipeline` (`bip39-auth-core`)

Привет! Провёл глубокий аудит репозитория `BIP39-Onboarding-pipeline-main` (TypeScript/React слой `src/` + Rust-крейт `crates/bip39-auth-rs`) на предмет реальной криптографической стойкости, memory safety и готовности к интеграции через C-ABI / JNI в Android-проект.

Сам пайплайн (12/24 слова, проверка 3 случайных слов с `exclude_indices`, разделение основного и Duress PIN, HKDF domain-separation) концептуально отличный, но в текущем коде найден ряд **критических крипто-ошибок и FFI-блокеров**, которые нужно исправить до продакшена и до подключения через FFI.

---

## БЛОКЕРЫ (исправить обязательно)

### 1. Фатальная уязвимость brute-force PIN и Duress PIN (`crates/bip39-auth-rs/src/storage.rs` и `src/services/cryptoStorage.ts`)
- **Где проблема:**
  - В `storage.rs:38-44, 80-89` и `cryptoStorage.ts:28-37, 95` в JSON-контейнер открыто записываются:
    - `pin_hash_hex = SHA256(pin || salt)`
    - `duress_hash_hex = SHA256(duress_pin || salt)`
- **Почему это критично:**
  - `SHA-256` без растяжки (1 раунд) перебирается на любом GPU за миллисекунды для всех 1 000 000 возможных 6-значных PIN-кодов. Злоумышленнику, получившему файл/строку хранилища, вообще не нужно атаковать PBKDF2 (100 000 раундов) — он мгновенно узнаёт и основной PIN, и Duress PIN, проверяя `SHA256(candidate || salt)`.
  - Кроме того, в `storage.rs:111, 121` и `cryptoStorage.ts:132` используется обычное сравнение строк (`==` / `!=`) вместо constant-time сравнения (`subtle::ConstantTimeEq`).
- **Как исправить:**
  1. Полностью убрать `pin_hash_hex` из JSON — факт верного основного PIN доказывается успешной аутентифицированной расшифровкой `Aes256Gcm` (AEAD tag verification).
  2. Для `duress_pin` использовать **отдельную случайную 16-байтную соль** (`duress_salt_hex`) и минимум **120 000 итераций PBKDF2-HMAC-SHA256** (либо Argon2id), сравнивая теги через `subtle::ConstantTimeEq`.
  3. Ещё лучше: вычислять симметричные ветки с одинаковым временем выполнения или проверять Duress-ветку через PBKDF2 с отдельной солью и фиксированным набором раундов (желательно >= 120 000–210 000 по рекомендациям OWASP).

### 2. Несоответствие зависимостей в `crates/bip39-auth-rs/Cargo.toml`
- **Где проблема:**
  - `src/cloud_storage.rs:10-11` импортирует `use hkdf::Hkdf;` и `use rand_core::{OsRng, RngCore};`, но в `crates/bip39-auth-rs/Cargo.toml` нет ни `hkdf`, ни `rand_core` (есть только `hmac` и `rand`).
- **Как исправить:**
  - Явно добавить `hkdf = "0.12.4"` и `rand_core = { version = "0.6.4", features = ["getrandom"] }` в `[dependencies]` и проверить `cargo test --lib` и `cargo check --target aarch64-linux-android`.

### 3. Паники (`panic!`) в FFI и AES-GCM при `panic = "abort"`
- **Где проблема:**
  - В `Cargo.toml:52` включён `panic = "abort"`. При этом:
    - `storage.rs:130`: `Nonce::from_slice(&iv_bytes)` **вызовет `panic!`** (краш всего процесса Android/iOS), если длина `iv_bytes != 12`.
    - `cloud_storage.rs:86`: `Nonce::from_slice(&iv)` — аналогичный `panic!` при `iv.len() != 12`.
    - `ffi.rs:29, 58, 91, 122, 151, 175`: `.unwrap()` на `CString::new(...)` (вынести в безопасный helper, возвращающий `ptr::null_mut()`).
    - `key_derivation.rs:87`: `.try_into().unwrap()`.
- **Как исправить:**
  - Перед `Nonce::from_slice(...)` проверять `if iv.len() != 12 { return Err(...); }`.
  - В `ffi.rs` полностью отказаться от `.unwrap()` и добавить `catch_unwind` на границе FFI-вызовов.

### 4. Отсутствие C-ABI экспортов для `storage.rs`, `cloud_storage.rs` и `mnemonic::generate_verification_challenges`
- **Где проблема:**
  - В `crates/bip39-auth-rs/src/ffi.rs` сейчас экспортированы только мнемоника и Web3 (`erc4337`, `stealth`, `aml`, `derive_accounts_json`), но **не экспортированы**:
    - шифрование/расшифровка vault + проверка Duress PIN (`storage.rs`),
    - HKDF-деривация ключей из seed (`derive_cloud_key`) и `encrypt_for_cloud` / `decrypt_from_cloud` (`cloud_storage.rs`),
    - генерация верификационных челленджей с `exclude_indices` (`mnemonic.rs`).
- **Как исправить:**
  - Добавить C-ABI (или UniFFI) функции именно для:
    - `bip39_validate_mnemonic(words_c) -> c_int`
    - `bip39_mnemonic_to_seed_hex(words_c, passphrase_c) -> *mut c_char` (или возвращать обёртку ключа/HKDF прямо внутри Rust без передачи сырого seed в JVM/JS heap!)
    - `bip39_derive_domain_key_hex(mnemonic_c, passphrase_c, namespace_c) -> *mut c_char` (HKDF-SHA256 внутри Rust с немедленной `Zeroize` seed-буфера!)
    - `bip39_generate_challenges_json(words_json_c, exclude_indices_json_c) -> *mut c_char`
    - `bip39_encrypt_vault(...)` / `bip39_decrypt_vault(...)`
  - **Важно:** добавить в `ffi.rs` функцию `bip39_zeroize_and_free_string(ptr: *mut c_char)`, которая затирает строку нулями (`volatile` / `zeroize`) перед освобождением `CString::from_raw`, а не просто вызывает `drop`.

---

## СУЩЕСТВЕННЫЕ ЗАМЕЧАНИЯ ПО КРИПТОГРАФИИ И БЕЗОПАСНОСТИ

### 5. Ложный статус «encrypted» в PDF-экспорте (`src/services/pdfExport.ts:109, 138`)
- **Где проблема:**
  - В тексте PDF написано: *"This QR code contains your encrypted BIP-39 mnemonic phrase"*, однако в `pdfExport.ts:109` вызывается `generateQrDataUrl(mnemonicWords.join(' '))` — то есть в QR зашивается **открытая (незашифрованная) seed-фраза**.
- **Как исправить:**
  - Либо честно переименовать предупреждение (убрать слово *encrypted*), либо предварительно шифровать мнемонику паролем/PIN через AES-256-GCM перед генерацией QR-кода (рекомендуемый вариант для мобильного шеринга).

### 6. Двойное кеширование в EIP-191 подписи (`crates/bip39-auth-rs/src/signer.rs:32-40, 67-84`)
- **Где проблема:**
  - Сначала считается `digest = Keccak256("\x19Ethereum Signed Message:\n" + len + msg)`, а затем передаётся в `sign_digest_recoverable(Keccak256::new().chain_update(&digest))`, который **второй раз** хэширует уже готовый `digest`.
  - В результате `sign_ethereum_message` несовместим с MetaMask / `ethers.js` / `alloy` / Ethereum `ecrecover` (подпись проверяется только собственной же `verify_eip191_signature`, которая тоже дважды хэширует).
- **Как исправить:**
  - Передавать в `sign_digest_recoverable` / `recover_from_digest` префикс + сообщение в один `Keccak256`:
    `signing_key.sign_digest_recoverable(Keccak256::new().chain_update(prefix.as_bytes()).chain_update(message.as_bytes()))`.

### 7. Заглушечная HD-деривация для ETH/BTC/SOL в `crates/bip39-auth-rs/src/key_derivation.rs`
- **Где проблема:**
  - Несмотря на заявленные пути `m/44'/60'/0'/0/0`, `m/84'/0'/0'/0/0` и `m/44'/501'/0'/0'`, реальный BIP-32/BIP-44/SLIP-0010 спуск по дочерним индексам в Rust-файле **не выполняется** — везде используются первые 32 байта корневого `HMAC-SHA512` (master node).
  - Более того, `btc_address` (`key_derivation.rs:80`) формируется как `format!("bc1q{}", hex::encode(btc_witness_prog))` вместо настоящего Bech32-кодирования (а `bech32` в `Cargo.toml` даже не используется!). При этом берётся публичный ключ от ETH (`eth_pub_compressed`).
- **Как исправить:**
  - Подключить честный BIP-32 / SLIP-0010 (`bip32` или `tiny-hderive` + реальный `bech32::encode`) и сверить векторы с BIP-39 / Ian Coleman test vectors, если этот модуль остаётся в SDK.

### 8. Слабый PRNG на TypeScript-стороне (`src/services/bip39.ts:408, 434`) и хранение seed в памяти/localStorage
- **Где проблема:**
  - В `bip39.ts:408, 434` для перемешивания и выбора слов верификации используется `Math.random()` вместо `crypto.getRandomValues`.
  - В `BIP39Onboarding.tsx` и типах `WalletCredentials` сырой `seedHex` и `mnemonic` передаются в JS-объект целиком.
- **Как исправить:**
  - Заменить `Math.random()` на rejection-sampling поверх `crypto.getRandomValues(new Uint32Array(1))`.
  - Для React/Android клиентов при работе с Rust-ядром не отдавать сырой `seedHex` на UI-уровень после онбординга — хранить мастер-ключ только в zeroizing enclave или hardware Keystore, а наверх отдавать лишь domain-separated ключи через `derive_cloud_key`.

---

## Чек-лист перед повторным подключением к Android-проекту **Paranoid**
1. [ ] Удалён быстрый `SHA-256` `pin_hash_hex`; Duress PIN проверяется через отдельную соль + `PBKDF2 >= 120_000` (или Argon2id) + `ConstantTimeEq`.
2. [ ] `Cargo.toml` собирается чисто (`hkdf` и `rand_core` добавлены), проходят `cargo test --lib` и `cargo check --target aarch64-linux-android`.
3. [ ] Убраны все `panic!` / `.unwrap()` из `storage.rs`, `cloud_storage.rs`, `ffi.rs`; проверяется `iv.len() == 12`.
4. [ ] В `ffi.rs` добавлены C-ABI/UniFFI методы для `bip39_validate_mnemonic`, `bip39_derive_domain_key_hex` (HKDF-SHA256 внутри Rust с `ZeroizeOnDrop`), `bip39_generate_challenges_json` и `bip39_zeroize_and_free_string`.
5. [ ] В `pdfExport.ts` QR-код либо зашифрован AES-GCM до рендера, либо явно помечен как plaintext (без вводящей в заблуждение надписи "encrypted").
6. [ ] В `signer.rs` исправлено двойное Keccak256-хеширование EIP-191; в `key_derivation.rs` исправлен BIP-32/Bech32 (если Web3-ветка остаётся в SDK).
