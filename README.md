# Akin Wallet

A local encrypted vault for social accounts, bank cards, and six Philippine government ID types. The application uses Java, XML Views, Material 3, and SQLCipher SQLite. Its screens are portrait-only and its runtime has no Internet permission or remote API requirement.

## Features and behavior

- Social accounts: a fixed platform catalog, credentials, and directed links to other accounts.
- Bank cards: Debit/Credit/Prepaid, Visa/MasterCard, five designs, masked dashboard previews, and existing field validation.
- Government IDs: National ID, Driver's License, Passport, SSS, PhilHealth, and TIN with document-specific forms and horizontal previews.
- Dashboard: a fixed parent with horizontal ID/card carousels and an internally scrolling social list.
- Search: local substring matching, including the existing numeric card-number alternative; no password, PIN, or CVV indexing.
- Settings: a fixed parent, opt-in strong biometrics, PIN change, Trash, and bundled policies.
- Trash: soft deletion, restore preserving recency, and confirmed permanent deletion.

Opening a record updates its recency before navigation. Restore clears deletion without changing recency. Account links remain directed; temporary deletion preserves them and permanent deletion cascades them.

## Security and ownership

SQLCipher encrypts the private database with a random 256-bit key. Android Keystore wraps that key with AES-GCM; hardware protection depends on the device. The application PIN verifies a salted PBKDF2-HMAC-SHA256 hash with 120,000 iterations and is independent of the encryption key.

Authentication state is process-only. Cold starts require authentication; a background period of 60 seconds expires the session. Serialized PIN verification reserves each attempt durably before hashing. Five failed/interrupted attempts start a 30-second cooldown, escalating to a 30-minute cap. Pending cooldowns use elapsed time and boot identity. Incorrect PINs never delete the vault.

VaultStore owns one serialized database executor and the application context. It admits authenticated work, invalidates snapshots after committed writes, and closes the database/key buffers after lock. Dashboard projections omit account passwords/PINs and card CVVs/PINs; details load by ID for editors. Screen ViewModels retain drafts only in memory. Process death discards unsaved sensitive drafts; saved-instance bundles and automatic sensitive view-state saving do not preserve them.

Backup is disabled, and private storage domains are explicitly excluded from cloud backup and device transfer. Vault windows/dialogs use FLAG_SECURE. Sensitive fields disable autofill, content capture where supported, and keyboard learning. Existing text editing interactions are retained.

Encryption protects files at rest; it cannot stop code executing with the application's privileges on a compromised device. The PIN does not cryptographically authorize Keystore key use. Clipboard behavior follows Android's standard editing controls. JVM/native memory erasure and physical flash erasure are not guaranteed.

Missing or invalid existing keys and incompatible development schemas fail closed, preserving database files. There is no automatic reset or recovery workflow. Uninstalling or losing the device/Keystore can permanently destroy access.

## Build

Android 8.0 / API 26 minimum; compile/target SDK 37. The wrapper pins Gradle 9.6.0, AGP 9.4.1, and a JDK 25 daemon. Application Java source/target remain 11.

Set sdk.dir in local.properties through Android Studio. Build-time dependency resolution may require Internet on a fresh machine; normal application operation does not.

    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    python3 tools/test_schema_v1.py

Debug uses the separate com.akin.wallet.debug application ID. Unit tests and debug configuration do not require release credentials.

Signed release packaging requires release.storeFile, release.storePassword, release.keyAlias, and release.keyPassword in local.properties. Keep credentials and private signing files outside source control and logs. Missing credentials fail release packaging without silently using a debug key.

    ./gradlew :app:lintRelease :app:assembleRelease :app:bundleRelease
    python3 tools/inspect_release.py

Release enables R8 code/resource optimization. Runtime/test graphs are locked; dependency artifacts have SHA-256 verification metadata. Review checksum/lock changes deliberately when changing dependencies.

Build one optimized APK with `:app:assembleRelease` or `:app:assembleDebug`. Both enable code and resource shrinking by default; ABI split outputs are disabled. Instrumentation tasks automatically use an unminified debug build for test-runner compatibility; use `-PoptimizeDebug=false` for full debugging. Production and development packages have separate vaults; changing packages does not transfer records. See [measured sizes and build instructions](docs/apk-size-optimization.md).

## Database V1

The authoritative schema is app/src/main/res/raw/vault_schema_v1.sql. Tables: social_accounts, bank_cards, id_cards, and account_links. Records use 64-bit identifiers, checked writes, non-null business values, audit timestamps, partial ordering indexes, and directed foreign-key links.

Social-record/link saves and mixed Trash actions are atomic transactions. Overview projections do not load editor-only credentials. Cursors use try-with-resources. V1 has no development migration history or destructive upgrade fallback.

## Code structure

- activity/: screens, memory-only screen ViewModels, authenticated publication/navigation.
- adapter/: lists, horizontal previews, catalog/link selection, and Trash.
- db/: VaultStore and the SQLCipher helper.
- model/: immutable records, platform catalog, and fixed government-ID field contracts.
- security/: VaultSession, PIN/cooldown preferences, and Keystore wrapping.
- ui/: fixed portrait dashboard and carousel measurement.
- util/: document-face rendering/formatting, UI behavior, and a 2 MiB public-artwork cache.

The artwork cache contains public catalog logos only. One application-owned worker downsamples them off the main thread. Requests retain weak ImageView references, share in-flight decodes, and reject results for recycled views. It clears on memory pressure; resource IDs are fixed for the installed application, so SQLite mutations do not invalidate it.

## Verification and release status

See docs/plan.md for task status and docs/engineering-report.md for implementation evidence, measurements, and outstanding acceptance checks. Tests under src/androidTest use unique synthetic databases/preferences; they never reset the user's vault.

Automated application validation passed: 39 unit tests, 32 native device tests, and 9 host schema tests. The [portrait responsiveness report](docs/portrait-responsiveness.md) documents pixel/density/font matrices, complete card text and the floating Social FAB. The [3,000-record performance report](docs/performance-1000-records.md) documents caching and measured limits. Debug/release APKs and the AAB build successfully. Fresh compact-minified runtime, physical biometric, airplane-mode, TalkBack/visual/keyboard acceptance and broader persistence-fault/retention checks remain release gates.

© John Deniel Santos Dela Peña. All rights reserved. See the bundled Terms of Service.
