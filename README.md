# Muggle Wand Training — ACM Hackathon 2026

> *"Simulating real spells using Edge-AI."*

A Harry Potter-themed, multi-device AI experience built for the **Qualcomm Multiverse** hackathon track. An intelligent application that distributes inference and control across multiple Snapdragon-powered devices.

We used two Snapdragon devices:
- **Samsung Galaxy S25 Ultra**: edge AI inference (gesture & voice recognition)
- **Arduino UNO Q**: real-time physical actuation (LEDs, servo, & LED matrix)

The result: With your wand, cast a gesture and shout a spell name, watch physical objects move and lights change, as if you are performing magic in real life.

---

## The Concept

This system has intelligence split across devices the same way a real distributed system splits compute:

| Task | Device | Reasoning |
|---------|--------|----------|
| Computer vision & NLP | Samsung Galaxy S25 Ultra | Snapdragon NPU handles real-time camera & voice inference |
| Message routing | UNO Q Linux core | Runs a full Mosquitto MQTT broker, acting as a network hub |
| Physical actuation | UNO Q MCU core | Real-time control of servo, LEDs, LED matrix |
| Spell event display | Flask web app | Any browser on the local network can display spells being cast live |

The two Snapdragon devices communicate over a shared Wi-Fi network (iPhone hotspot) using MQTT, a lightweight publish and subscriber protocol designed for exactly this kind of IoT messaging.

---

## System Architecture

```
┌─────────────────────────────────────────────────────┐
│           Samsung Galaxy S25 Ultra                  │
│                                                     │
│  Camera  ──►  StickDetector (CV)  ──►  Spell ID     │
│  Mic     ──►  SpeechRecognizer    ──►  Spell ID     │
│                       │                             │
│           MQTT publish "spell/cast" → "1"           │
└───────────────────────┼─────────────────────────────┘
                        │  Wi-Fi  (iPhone hotspot)
                        ▼
┌─────────────────────────────────────────────────────┐
│              Arduino UNO Q — Linux core             │
│                                                     │
│   Mosquitto MQTT broker  (port 1883)                │
│   Python bridge script   subscribed to "spell/cast" │
│           │                                         │
│           │  RouterBridge / serial                  │
└───────────┼─────────────────────────────────────────┘
            │
┌───────────▼─────────────────────────────────────────┐
│              Arduino UNO Q — MCU core               │
│                                                     │
│   sketch.ino  ──►  LED matrix  (8×12 pixel art)    │
│               ──►  Servo motor  (pin 9)             │
│               ──►  Red LED      (pin 10)            │
│               ──►  Cup LED      (pin 11)            │
└─────────────────────────────────────────────────────┘

           ┌──────────────────────────────┐
           │   Flask web app (any device) │
           │   Subscribes to MQTT broker  │
           │   Streams events via SSE     │
           │   Browser shows live log     │
           └──────────────────────────────┘
```

The UNO Q is the only device on the network that is both a message broker *and* a physical actuator, its Linux core handles networking and routing while its MCU core drives hardware in real time.

---

## Spells

Spells can be casted by: **gesture** (waving the wand at the camera) and **voice** (shouting the spell name) at the same time.

| # | Gesture | Voice | Spell | Physical Effect |
|---|---------|-------|-------|-----------------|
| 1 | Swipe wand downward | *"Push"* | **PUSH** | Servo sweeps 0→180→0° knocking down objects on a table. |
| 2 | Swipe wand rightward | *"Lumos"* | **LUMOS** | Toggles red LED On & Off. |
| 3 | Draw a circle with the wand | *"Summon"* | **SUMMON** | Flashes the website page and spawns Voldemort. |
| 4 | — | *"Open"* | **OPEN** | Logs and Displays spells on the web app as well as plays sound effects. |

---

## How Spell Detection Works

### Camera path — gesture recognition

The Android app runs a custom computer vision pipeline entirely on-device, using the Snapdragon NPU via Android's native camera stack:

1. **Frame capture** — CameraX delivers YUV_420_888 frames on a dedicated background thread.
2. **Downscale** — each frame is scaled to 320×240 before analysis to reduce compute load.
3. **Red-dot segmentation** — `StickDetector` performs per-pixel HSV analysis looking for a vivid red marker attached to the tip of the wand.
   - Hue must be in [0°, 12°] or [348°, 360°] (the red wrap-around region)
   - Saturation ≥ 0.65, Value ≥ 0.35
   - RGB ratio guards: `R/G ≥ 1.8` and `R/B ≥ 1.6` — this is what rejects skin tones
   - At least 8 red pixels must form a blob (noise rejection)
4. **Centroid tracking** — the blob centroid is the wand tip position, recorded as normalised (0–1) screen coordinates.
5. **Gesture classification** — tip positions are accumulated over a rolling ~2.7 s / 80-sample window and evaluated every 1.5 s:
   - **PUSH** — average Y of the second half of the window is ≥ 0.15 below the first half (downward motion dominates)
   - **LUMOS** — average X of the second half is ≥ 0.15 to the right of the first half (rightward motion dominates)
   - **SUMMON** — the full window of points forms a near-circle: mean radius > 7% of frame, radius coefficient-of-variation < 55%, and the largest uncovered arc < 126°

### Voice path

`SpeechRecognizer` runs in parallel with the camera — the same recognizer instance is reused across listen cycles to avoid `ERROR_RECOGNIZER_BUSY`. When a result contains "PUSH", "LUMOS", "SUMMON", or "OPEN" it calls `publishSpell()` with the same numeric payload as the gesture path.

### MQTT message format

```
topic:   spell/cast
payload: "1" | "2" | "3" | "4"
```

The web app maps these back to human-readable names for display:

```python
SPELL_MAP = {"1": "PUSH", "2": "LUMOS", "3": "SUMMON", "4": "OPEN"}
```

---

## Project Structure

```
mca_2026/
├── app/                              # Android app (Kotlin)
│   └── src/main/java/com/example/wandapplication/
│       ├── MainActivity.kt           # Entry point: camera, MQTT, voice recognition
│       ├── StickDetector.kt          # CV pipeline: red-dot tracking + gesture classification
│       └── WandOverlayView.kt        # Canvas overlay drawn on top of the camera preview
│
├── web_app/                          # Spell event display (Python / Flask)
│   ├── app.py                        # MQTT subscriber + SSE broadcast to browsers
│   ├── templates/index.html          # Live spell log UI
│   ├── assets/                       # Static assets
│   └── requirements.txt             # flask>=3.0, paho-mqtt>=2.0
│
├── embedded/
│   ├── sketch/
│   │   ├── sketch.ino                # Arduino MCU firmware
│   │   └── sketch.yaml
│   └── python/
│       └── main.py                   # Linux-side bridge (MQTT → serial to MCU)
│
├── build.gradle.kts                  # Android Gradle build
└── settings.gradle.kts
```

---

## Setup & Running

### Network prerequisites

All devices must be on the same network. We use an **iPhone hotspot**.

| Device | Role | Default IP |
|--------|------|-----------|
| Arduino UNO Q | MQTT broker (Mosquitto, port 1883) | `172.20.10.5` |
| Samsung Galaxy S25 Ultra | Android app — publishes spells | connects to broker IP above |
| Any laptop/phone | Flask web app — displays spell events | connects to broker IP above |

If your network assigns a different IP to the UNO Q, update `MQTT_BROKER` in both `MainActivity.kt` and `web_app/app.py`.

---

### 1. Arduino UNO Q — Linux core

**Install Mosquitto broker:**
```bash
apt install mosquitto mosquitto-clients
mosquitto -d   # start in background
```

**Start the Python bridge** (routes MQTT messages to the MCU over serial):
```bash
cd embedded/python
pip install -r requirements.txt
python main.py
```

---

### 2. Arduino UNO Q — MCU core

Open `embedded/sketch/sketch.ino` in the Arduino IDE.

Install required libraries via the Library Manager:
- `Arduino_RouterBridge`
- `Arduino_LED_Matrix`

Upload to the UNO Q's MCU core. On boot you will see the LED matrix flash all-on briefly as confirmation.

**Pin assignments:**

| Pin | Connected to |
|-----|-------------|
| 9 | Servo motor signal |
| 10 | Red LED |
| 11 | Cup LED |
| Built-in | 8×12 LED matrix |

---

### 3. Android App (Samsung Galaxy S25 Ultra)

1. Open the project root in Android Studio.
2. Confirm `MQTT_BROKER` in `MainActivity.kt` matches your UNO Q's IP.
3. Build and deploy to the physical device (camera and microphone are required — the emulator will not work).
4. Grant **Camera** and **Microphone** permissions when prompted.
5. Tap **Open Camera**, then tap the preview to toggle wand detection on.

To cast a spell with the wand, attach a small vivid red sticker to the tip. The detector looks specifically for this colour signature — see the [How Spell Detection Works](#how-spell-detection-works) section above for why.

---

### 4. Flask Web App

Run on any machine that can reach the MQTT broker:

```bash
cd web_app
pip install -r requirements.txt
python app.py
```

Open `http://<host-ip>:5000` in a browser. Spell events stream in real time via SSE — no WebSocket, no polling.

---

## Hardware List

| Item | Purpose |
|------|---------|
| Samsung Galaxy S25 Ultra | Computer vision + voice inference device |
| Arduino UNO Q | MQTT broker (Linux core) + physical actuation (MCU core) |
| Servo motor | Moves on PUSH / Stupefy |
| Red LED | Toggles on LUMOS / NOX |
| Secondary LED ("cup LED") | Flashes on PUSH |
| Wand prop | The physical wand — any stick works |
| Small vivid **red sticker** | Affixed to the wand tip for the CV tracker to lock onto |
| iPhone (or any hotspot) | Shared Wi-Fi network between all devices |

---

## Hackathon Track

**Qualcomm Multiverse** — *"design applications that go beyond a single device, distributing inference and control across devices."*

This project distributes the workload as follows:
- **Inference** runs on the Samsung Galaxy S25 Ultra (Snapdragon, on-device — no cloud)
- **Routing & orchestration** runs on the Arduino UNO Q Linux core (Snapdragon)
- **Real-time actuation** runs on the Arduino UNO Q MCU core

No single device does everything. Remove either Snapdragon device and the system stops working.

---

## Frameworks & Libraries

### Android App

| Library | Version | Purpose |
|---------|---------|---------|
| [AndroidX CameraX](https://developer.android.com/training/camerax) (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) | 1.3.1 | Camera capture pipeline and per-frame image analysis |
| [Eclipse Paho MQTT Client](https://github.com/eclipse/paho.mqtt.java) (`org.eclipse.paho.client.mqttv3`) | 1.2.5 | MQTT publish/subscribe over TCP |
| [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) | Android SDK | On-device continuous voice recognition |
| [AndroidX Core KTX](https://developer.android.com/kotlin/ktx) | 1.10.1 | Kotlin extensions for Android core APIs |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.6.1 | Backwards-compatible Activity and Fragment support |
| [AndroidX ConstraintLayout](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintLayout) | 2.1.4 | Flexible UI layout |
| [AndroidX Activity](https://developer.android.com/jetpack/androidx/releases/activity) | 1.8.0 | `registerForActivityResult` permission launchers |
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.10.0 | `MaterialButton` and Material Design theming |
| [Android Gradle Plugin](https://developer.android.com/build) | 9.1.0 | Android build toolchain |

### Web App

| Library | Version | Purpose |
|---------|---------|---------|
| [Flask](https://flask.palletsprojects.com/) | ≥ 3.0 | HTTP server, routing, and Jinja2 templating |
| [paho-mqtt](https://github.com/eclipse/paho.mqtt.python) | ≥ 2.0 | MQTT client — subscribes to `spell/cast` and fans out to SSE clients |

### Embedded — Arduino MCU

| Library | Purpose |
|---------|---------|
| [Arduino_RouterBridge](https://github.com/arduino-libraries/Arduino_RouterBridge) | Serial bridge between the UNO Q Linux core and MCU core |
| [Arduino_LED_Matrix](https://github.com/arduino-libraries/Arduino_LED_Matrix) | Driver for the UNO R4's built-in 8×12 LED matrix |

### Embedded — Linux Core

| Tool | Purpose |
|------|---------|
| [Mosquitto](https://mosquitto.org/) | Lightweight open-source MQTT broker running on the UNO Q Linux side |

---

## Team

Built at the ACM Hackathon 2026.
