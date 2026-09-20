# Akin Wallet

An offline-first, encrypted vault for social-account credentials, bank cards, and Philippine
government IDs. No account, no sign-up, no server — everything stays encrypted on the device.

## Features

- **Social accounts** — usernames, passwords, and PINs across a 43-platform catalog
  (Google, Facebook, GCash, Maya, banks, streaming, gaming, and more), with related
  accounts linkable to each other.
- **Bank cards** — Debit / Credit / Prepaid on Visa / MasterCard, shown as masked previews
  with selectable card designs. Numbers validated to 16–19 digits (ISO 7812), CVV, expiry,
  and card PIN.
- **Philippine government IDs** — National ID, Driver's License, Passport, SSS, PhilHealth,
  and TIN, each rendered with its own document face, per-type number grouping, and dynamic
  spec-driven forms.
- **App lock** — 4-digit PIN gate on every cold start, optional fingerprint unlock
  (opt-in, `BIOMETRIC_STRONG`-gated), 5-attempt lockout with a 30-second monotonic-clock
  cooldown, and in-memory session state that always re-locks on restart.
- **Private search** — dashboard search filters in memory and never indexes secrets
  (no passwords, PINs, or CVVs in the search corpus).
- **Trash** — deletes are soft-deletes; restore individually or in bulk, or delete forever
  with confirmation. Selection survives rotation.
- **In-app legal** — Privacy Policy, Terms of Service, and About screens ship with the app
  under Settings; no webview, no network fetch.

## Security architecture

| Layer | Implementation |
|---|---|
| Vault encryption | SQLCipher (`net.zetetic:sqlcipher-android:4.19.0`), AES-256 over `akin_wallet.db` |
| Vault key | Random 256-bit `SecureRandom` key — never derived from the PIN. Sealed with an AES-GCM key in the hardware-backed Android Keystore (alias `akin_vault_db_key`); only the sealed blob touches disk. Unwrapped key lives in process memory only. |
| PIN storage | PIN is never stored. Salted PBKDF2-HMAC-SHA256, 120,000 iterations, 256-bit output, 16-byte salt, constant-time comparison (`MessageDigest.isEqual`). |
| Brute force | 5 wrong PINs → 30 s lockout on the monotonic clock (`elapsedRealtime`, immune to wall-clock changes). |
| Network | The manifest declares **no `INTERNET` permission** — the OS itself prevents exfiltration. Sole permission is `USE_BIOMETRIC`; fingerprint hardware is `required="false"` so Play never filters on it. |
| Backups | Vault database and wrapped key are excluded from cloud backup **and** device-to-device transfer (`backup_rules.xml`, `data_extraction_rules.xml`), because a Keystore-bound copy restored elsewhere could never be decrypted. |
| Release hardening | R8 full-mode shrink + obfuscation (`proguard-rules.pro`), resource shrinking, `debuggable=false`, APK Signature Schemes v2/v3/v4 with v1 (Jar signing / Janus surface) disabled, and fail-closed signing — a release build without the release key aborts instead of falling back to the debug key. |

Threat model, plainly: this protects data at rest against file reads (lost-device dumps,
backup snooping, `adb` pulls). It does not stop code running as the app on a rooted device,
which can ask the Keystore to unwrap just like the app does.

## Privacy

- No analytics, no crash reporting, no advertising identifiers, no location, no contacts.
- Nothing the user stores leaves the device through this app — there is no network code path.
- Uninstalling, factory-resetting, or wiping the device Keystore destroys access permanently.
  There is no recovery mechanism, by design — not even the developer can recover items.

## Requirements

- Android 7.0 (API 24) or later; targets Android 17 (API 37).
- Fingerprint unlock appears only on devices with strong biometric hardware, and only after
  it is enabled in Settings.

## Build from source

Requires JDK 17+ and the Android SDK (compileSdk 37).

```bash
git clone <repo-url>
cd "Akin Wallet"
# Point local.properties at your SDK (Android Studio does this automatically):
#   sdk.dir=/path/to/Android/Sdk
./gradlew :app:assembleDebug
```

A signed release additionally needs four keys in `local.properties` (never committed —
`.gitignore` already excludes it and `*.keystore`):

```properties
release.storeFile=/absolute/path/to/release.keystore
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

```bash
./gradlew :app:assembleRelease   # fails fast with a clear error if signing is missing
./gradlew :app:bundleRelease     # App Bundle for Play Store upload
```

Debug builds use `applicationIdSuffix ".debug"` so they never collide with the release app.

## Project structure

```
app/src/main/
├── java/com/akin/wallet/
│   ├── activity/      # Lock, Dashboard, SocialAccount, BankCard,
│   │                  # GovernmentID, Trash, Settings, Policy (8 screens, no fragments)
│   ├── adapter/       # Dashboard carousels, platform/design pickers, trash tiles
│   ├── db/            # AppDatabaseHelper — SQLCipher schema v1, soft-delete, indexes
│   ├── model/         # SocialAccount/Platform, BankCard, GovernmentID (+ ID field specs)
│   ├── security/      # DbKeyManager (Keystore-wrapped vault key),
│   │                  # AppLockManager (PBKDF2 PIN, lockout, biometric state, session)
│   └── util/          # Ui (system bars, dialogs, snackbars), CardText, renderers
└── res/
    ├── values/strings.xml  # All user-facing copy incl. full privacy/terms/about text
    └── xml/                # backup_rules + data_extraction_rules (vault excluded)
```

Schema v1: `social_accounts`, `account_links` (many-to-many), `bank_cards`, `id_cards`
(JSON document fields). Deletes stamp `deleted_at`; Trash restores or purges per type.

## Tech stack

| Dependency | Version | Notes |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Gradle 9.6.0 wrapper, Java 11 bytecode |
| `androidx.appcompat:appcompat` | 1.8.0 | |
| `androidx.biometric:biometric` | 1.1.0 | Latest stable; `BIOMETRIC_STRONG` + `BiometricPrompt` |
| `com.google.android.material:material` | 1.14.0 | Material 3 Expressive components |
| `androidx.constraintlayout:constraintlayout` | 2.2.2 | |
| `net.zetetic:sqlcipher-android` | 4.19.0 | Vault encryption (native libs) |
| `androidx.sqlite:sqlite` | 2.7.1 | Compile-only: satisfies SQLCipher's `SupportSQLiteDatabase` type |

## Distribution

Play Store only. Release APKs are signed v2/v3/v4 (no v1) and R8-obfuscated — sideloaded
copies cannot be updated by, or impersonate, the Play release. Upload the
`bundleRelease` AAB with Play App Signing.

## Roadmap

- Automated tests (`src/test`, `src/androidTest` — currently none).
- Play Integrity API verdict gating the vault on tampered installs.
- Store-listing screenshots and a changelog.

## License

© John Deniel Santos Dela Peña. All rights reserved — see Terms of Service in-app.
Contact: johndenieldelapena97@gmail.com.
