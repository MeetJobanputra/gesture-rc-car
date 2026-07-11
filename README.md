# Gesture RC Car

Android app: point your phone's camera at your hand, it recognizes the gesture
(MediaPipe), and sends a matching command over Classic Bluetooth to your
HC-05/HC-06-equipped Arduino car.

## Default gesture mapping

| Gesture | Emoji | Command sent | Meaning |
|---|---|---|---|
| Thumb_Up | 👍 | `F` | Forward |
| Thumb_Down | 👎 | `B` | Backward |
| Closed_Fist | ✊ | `S` | Stop |
| Open_Palm | ✋ | `S` | Stop |
| Victory | ✌️ | `L` | Left |
| ILoveYou | 🤟 | `R` | Right |
| (no hand seen for 0.8s) | — | `S` | Auto-stop safety net |

**These single letters (F/B/L/R/S) are the most common convention used in
Arduino Bluetooth-car tutorials — but they need to match whatever your
Arduino sketch already listens for.** Open `MainActivity.kt` and look for the
`gestureCommandMap` near the top of the class — edit the characters there if
your sketch expects something else. Everything else in the app works
regardless of what characters you use.

## 1. Prerequisites

- [Android Studio](https://developer.android.com/studio) (free), latest stable version
- A physical Android phone, Android 7.0 (API 24) or newer, with a camera and Bluetooth
  - **Must be a real device** — emulators generally can't do live camera + classic Bluetooth
- A USB cable to connect the phone to your computer
- Your car's HC-05/HC-06 already wired up and working (as it already is)

## 2. Pair the HC-05 with your phone FIRST

This has to be done once, at the Android OS level, before the app can use it:

1. On your phone: **Settings → Bluetooth → Pair new device**
2. Select your HC-05/HC-06 (often named `HC-05`, `HC-06`, or similar)
3. Enter the PIN — almost always `1234` or `0000` for these modules
4. Confirm it now shows up as a paired device

## 3. Open and run the project

1. Open Android Studio → **Open** → select the `GestureRcCar` folder
2. Let Gradle sync. If Android Studio offers to upgrade the Android Gradle
   Plugin/Kotlin version for you, accept it — that's just it matching the
   versions to your installed Studio version.
3. On your phone: enable **Developer Options** (tap Build Number 7 times in
   Settings → About phone) then enable **USB debugging** inside Developer Options
4. Plug your phone in via USB, allow the debugging prompt on the phone
5. In Android Studio, select your phone from the device dropdown and click **Run ▶**

The first build will auto-download the gesture recognition model
(`gesture_recognizer.task`, a few MB) into `app/src/main/assets/`. If your
network blocks this, download it manually from:
`https://storage.googleapis.com/mediapipe-tasks/gesture_recognizer/gesture_recognizer.task`
and place it at `app/src/main/assets/gesture_recognizer.task` yourself.

## 4. Using the app

1. Grant the camera and Bluetooth (or Location, on older Android) permissions when asked
2. Tap **Connect to Car**, pick your HC-05 from the list
3. Hold your hand up in front of the camera and try the gestures above
4. The top label shows the recognized gesture + confidence; the bottom label
   shows the last command sent and connection status

## Troubleshooting

- **"Connection failed"** — make sure the HC-05 isn't already connected to
  something else (e.g. your old app, or still powered oddly). Only one
  device can hold the Bluetooth connection at a time.
- **App can't find any paired devices** — go back and do step 2 (pairing) first.
- **`UnsatisfiedLinkError` / native library errors on launch** — do
  **Build → Clean Project** then **Rebuild Project** in Android Studio; this
  is usually a stale-build issue with the MediaPipe native libraries.
- **Gestures feel laggy or misfire** — the confidence threshold is set at
  `MIN_GESTURE_SCORE = 0.6f` in `MainActivity.kt`; raise it for stricter
  matching, lower it if it's not registering your gestures at all. Good, even
  lighting also helps a lot.
- **Car doesn't respond at all even though "Sent: F" shows on screen** —
  double check the command characters actually match your Arduino sketch's
  `if (command == 'F')` (or similar) logic, and that both are running at the
  same serial baud rate the HC-05 is configured for (commonly 9600).

## Customizing further

- To also show live hand-landmark skeleton overlay on screen (nice demo
  touch for judges), MediaPipe's `GestureRecognizerResult` also returns hand
  landmarks (`result.landmarks()`) — ask if you'd like this added.
- To add more distinct commands (e.g. dedicated turn speeds), you can use
  the `Pointing_Up` category too, or use hand landmark positions directly for
  finer control (e.g. steering by hand tilt angle) instead of the fixed
  gesture set.
