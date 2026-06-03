# Byte2Bites — Buyer App

Byte2Bites is a food‑ordering marketplace. This repository contains the **buyer-side Android application**, where customers browse sellers, order food, track orders, manage their profile, and place in‑app voice (VoIP) calls to sellers.

> This is the buyer client only. Sellers use a separate companion app; both share the same Firebase backend.

---

## Features

- **Authentication** — Register, log in, and password reset via Firebase Authentication.
- **Browse & shop** — Discover sellers, view a seller's products, and inspect product details.
- **Cart & checkout** — Add items to a cart, choose delivery or pickup, and place orders.
- **Order tracking** — Follow each order through its lifecycle (`WAITING_APPROVAL`, `PREPARING`, etc.).
- **Loyalty & wallet** — Earn loyalty points and maintain a wallet credit balance (stored in cents to avoid floating‑point errors), with point‑to‑credit conversion.
- **Addresses** — Save and manage delivery addresses.
- **Profile management** — Update user info, change password, and upload a profile picture.
- **In‑app VoIP calling** — Peer‑to‑peer UDP voice calls between buyer and seller, with Firebase used for call signaling.
- **Notifications** — Order and activity notifications.

---

## Tech Stack

| Area | Technology |
|------|------------|
| Language | Kotlin |
| UI | Android Views + View Binding, ViewPager2, Material Components |
| Backend | Firebase (Authentication, Realtime Database, Analytics) |
| Storage | AWS S3 (profile pictures / file uploads) |
| Location | Play Services Location |
| Image loading | Glide |
| Animations | Lottie |
| Voice | Custom UDP audio engine for VoIP |
| Build | Gradle (Kotlin DSL) |

---

## Architecture Overview

The app uses a single‑host `MainActivity` with a `ViewPager2` + bottom navigation hosting the **Home**, **Orders**, and **Profile** fragments. Individual flows (auth, cart, product details, address picking, VoIP, etc.) are separate Activities.

### Key data models

- **`User`** — display name, email, phone, profile photo URL, loyalty `points`, and wallet `credit` (in cents).
- **`Order`** — order total, items, delivery fee, delivery type (`DELIVERY` / `PICKUP`), status, and VoIP connection info. Mirrored in the database under both `/Buyers/{uid}/orders/{orderId}` and `/Sellers/{uid}/orders/{orderId}`.
- **`VoipCall`** — call signaling object stored under `/VoipCalls/{callId}` with statuses `INITIATED`, `RINGING`, `CONNECTED`, `ENDED`.
- Other models: `Product`, `Seller`, `Cart`/`CartItem`, `Address`.

### Project layout

```
app/src/main/
├── java/com/byte2bites/app/   # Activities, Fragments, Adapters, data models, VoIP engine
├── res/
│   ├── layout/                # XML layouts for activities & list items
│   ├── drawable/, anim/       # Icons, backgrounds, transitions
│   └── values/                # colors, strings, themes
└── AndroidManifest.xml
```

---

## Requirements

- Android Studio (latest stable recommended)
- JDK 8+
- Android SDK with API level 34
- Minimum device API level 24 (Android 7.0)

---

## Setup

### 1. Clone the repository

```bash
git clone <repo-url>
cd Byte2Bites-Buyer
```

### 2. Configure Firebase

This project requires a `google-services.json` file in the `app/` directory, connected to a Firebase project with **Authentication**, **Realtime Database**, and **Analytics** enabled.

> A `google-services.json` is already present in the repo for the original project. Replace it with your own Firebase project's file if you are deploying your own backend.

### 3. Configure AWS S3

The app uses AWS S3 to upload and store photos (such as profile pictures). Supply your AWS S3 credentials and bucket configuration as expected by the app's upload code.

---

## Build & Run

Using the Gradle wrapper:

```bash
# Build a debug APK
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug
```

Or simply open the project in **Android Studio**, let Gradle sync, select a device, and press **Run**.

---

## Permissions

The app declares the following permissions in the manifest:

- `INTERNET`, `ACCESS_NETWORK_STATE` — networking and backend access
- `RECORD_AUDIO` — VoIP voice calls
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — address / location selection
- `POST_NOTIFICATIONS` — order and activity notifications
- `READ_EXTERNAL_STORAGE` — selecting images for upload

---

## Notes

- Monetary values (credit, order totals, fees) are stored as **integer cents** throughout to avoid floating‑point rounding issues.
- VoIP audio travels over a direct UDP connection between peers; Firebase is used only for call signaling and exchanging connection details.
- `local.properties` and other secret/config files are git‑ignored — never commit API keys.
