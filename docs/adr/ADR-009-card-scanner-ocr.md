# ADR-009: Card scanner with CameraX and ML Kit's bundled text recognition

- Status: **Accepted** for milestone M5 (owner, 2026-10-15). Built with CameraX 1.6.2 and ML Kit `text-recognition` 16.0.1; the on-phone part of the review below (R13) still waits for a real phone.
- Date: 2026-10-15

## Context

The owner wants to add a card by pointing the camera at it, so the number, expiry and name do not have to be typed ([product plan](../product-plan.md) §4, "Adding a card"). Decided with the owner:

- **Front of the card only.** The CVV is always typed by hand, and the camera never reads the back.
- The app keeps **no INTERNET permission** (threat-model I5).
- Nothing the camera sees is ever written to storage.

A camera pointed at a payment card is a new trust boundary (threat-model B7, T35).

## Decision

| Library | Role | Notes |
|---|---|---|
| `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | Preview and frame delivery | Jetpack, Google-maintained. **Only the `ImageAnalysis` use case is bound.** `ImageCapture` and `VideoCapture` are never bound, so no photo or video is produced |
| `com.google.mlkit:text-recognition` (Latin script, **bundled model**) | On-device text recognition | The model ships inside the APK. The `play-services-mlkit-text-recognition` variant is **not** used, because it downloads its model through Google Play services |

The card-text parser is **our own Kotlin code**, with unit tests:

- **Number:** 13–19 digits once spaces are removed, passing the Luhn check, read the same in **3 consecutive frames**.
- **Network:** suggested from the number's prefix (Visa 4; Mastercard 51–55 and 2221–2720; RuPay 60, 65, 81, 82, 508; American Express 34, 37; Diners Club 36, 38). Some ranges overlap, so the user can always change it.
- **Expiry:** `MM/YY` with month 01–12, preferring the date nearest "VALID THRU" or "EXPIRES".
- **Name:** a line of uppercase letters and spaces, 2–26 characters, not matching a bank or network word.
- **Bank:** never set by the scanner. If a bank-catalogue name or alias ([ADR-011](ADR-011-bank-catalogue.md)) is printed on the card, it is offered as an unconfirmed suggestion ("Seen on card: HDFC Bank") that the user must tap.
- **Card kind (debit or credit), CVV and PIN:** never read.

Rules:

- CAMERA is requested at the moment the user taps **Scan**, never at install or start-up.
- FLAG_SECURE on the scanner screen, as on every screen.
- Each frame's `ImageProxy` is closed as soon as recognition returns. Recognised text goes to the parser, and the parser returns only the three fields; all other text is dropped.
- Nothing is logged. No frame or text crosses into `vault-core`; only the confirmed form values are saved, through the normal item write.
- The camera is unbound when a scan succeeds, when the user leaves the screen and when the vault locks.
- The release manifest is checked in CI: CAMERA present; INTERNET and ACCESS_NETWORK_STATE absent. Any entry merged in by a library is removed with `tools:node="remove"`.

## As built (milestone M5)

- `androidx.camera:camera-camera2`, `camera-lifecycle`, `camera-view` **1.6.2**; `com.google.mlkit:text-recognition` **16.0.1** (bundled model).
- The bundled artifact also pulls in Google's `play-services-mlkit-text-recognition` client and Play-services base libraries as compile dependencies. The app uses the bundled model; R13 must confirm on a phone that nothing is downloaded or written.
- ML Kit's usage reporting (`com.google.android.datatransport`) merges `INTERNET` and `ACCESS_NETWORK_STATE` into the manifest. Both are removed with `tools:node="remove"`, and `tools/manifest_assert.py` fails the build if either returns. Without the permission, the reporting cannot reach the network.
- The preview uses a TextureView (`PreviewView` in COMPATIBLE mode), so the window's FLAG_SECURE also covers the camera picture.
- `CardTextReader` (Kotlin) is the parser; the bank suggestion uses the catalogue's normaliser through `VaultEngine.findBank`.

## Review before real cards (research item R13)

- Read ML Kit's merged manifest entries and confirm none of them need the network.
- Run a scan on the A12 with `strace` or file-access monitoring, and confirm nothing is written under the app's storage (model cache, logs, telemetry queue).
- Record the exact versions and APK size added.

## Alternatives considered

| Option | Why not chosen |
|---|---|
| Google Pay "payment card recognition" intent | Runs inside Google Play services, outside our process and our FLAG_SECURE. It needs Google Play services and gives us no control over what it keeps |
| ML Kit with the Play-services (unbundled) model | Downloads the model at run time; we would depend on Play services and the network |
| Tesseract (tess-two and forks) | Native C++ code, poorer accuracy on embossed card fonts, larger, and unmaintained forks |
| Writing our own recogniser | Far more work and risk for one convenience feature |
| No scanner | Simplest and safest, but the owner asked for it; manual entry stays available |

## Consequences

- Several megabytes of APK per CPU architecture for the bundled model.
- A new dependency with Google as the maintainer, inside a security app. With no network permission it has no direct way out (T35), but it still sees card numbers in memory.
- Embossed, worn or unusually printed cards may not scan; manual entry always remains.

## Revisit when

- A reviewed, smaller on-device recogniser becomes available.
- R13 finds any file writes or network attempts.
