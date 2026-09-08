# 🌐 OmniNode Hub

> **Edge-First, Privacy-Preserving Smart Home Hub & Ambient Voice Assistant for Android**  
> *Transforming mobile devices into autonomous, zero-cloud IoT coordinators.*

---

## 🌟 Executive Summary

Traditional smart speakers (such as Amazon Echo or Google Nest) stream private household audio to remote cloud servers, suffer from internet latency, and fail completely during network outages. At the same time, millions of capable Android smartphones end up unused in drawers as e-waste.

**OmniNode Hub** solves both problems by upcycling Android smartphones into a centralized, edge-computed smart home hub and voice assistant. It runs 24/7 on-device, offering **zero-cloud privacy**, **instant response latency**, and **offline resilience**.

---

## ✨ Key Features & Innovations

- **🎙️ Ambient 24/7 Wake Word & Hardware Mic Bridge**:
  - Offline keyword spotting ("Hey Omni" / "Porcupine") powered by Picovoice Porcupine running in an Android Foreground Service.
  - Safe coroutine-based microphone lifecycle handoff preventing hardware contention and `ERROR_AUDIO` crashes on OEM chipsets (e.g., Xiaomi/Redmi).
- **💬 Two-Tier Conversational Engine**:
  - **Tier 1 (Spoken Dialogue):** Natural, conversational spoken responses via Android Text-to-Speech (TTS) instead of robotic raw JSON outputs.
  - **Tier 2 (Action Dispatch):** Fast, asynchronous IoT execution mapping intent to device traits.
- **📱 System-Wide Floating Assistant Overlay**:
  - Google Assistant-style bottom-sheet overlay built with **Jetpack Compose** (`SYSTEM_ALERT_WINDOW`).
  - Real-time live speech transcription as the user speaks, with automatic completion state and graceful auto-dismiss.
- **🏠 Universal IoT & Smart Door Lock Control**:
  - **Home Assistant WebSocket API:** Sub-20ms bidirectional event streaming and service execution.
  - **Wi-Fi Smart Locks:** First-class support for locking, unlocking, and telemetry.
  - **Matter over Wi-Fi:** Google Home APIs SDK integration for commissioning and direct local control.
- **🛡️ Graceful Offline Standalone Mode**:
  - Built-in in-memory `VirtualDeviceRegistry`. If Home Assistant is not present on the network, OmniNode silently falls back to a simulated device environment so voice commands and UI demos remain 100% functional without connection error alerts.

---

## 🏗️ System Architecture

```
                               ┌────────────────────────────────┐
                               │       User Voice Utterance     │
                               └────────────────┬───────────────┘
                                                │
                                 [Picovoice Porcupine (0.85 Sens)]
                                                │ ("Hey Omni")
                                                ▼
                               ┌────────────────────────────────┐
                               │ 300ms Hardware Mic Safe Bridge │
                               └────────────────┬───────────────┘
                                                │
                                 [Android SpeechRecognizer (en)]
                                                │
                                                ▼
                               ┌────────────────────────────────┐
                               │      NlpParser / Edge LLM      │
                               └────────┬───────────────┬───────┘
                                        │               │
                 ┌──────────────────────┴─┐           ┌─┴────────────────────────┐
                 ▼                        ▼           ▼                          ▼
          [Spoken TTS Output]    [Floating Overlay]  [Home Assistant WS]  [Virtual Registry]
```

---

## 📂 Project Structure

```
OmniNode_Hub/
├── app/src/main/
│   ├── AndroidManifest.xml                  # Permissions, overlay window, foreground services
│   ├── java/com/omninode/hub/
│   │   ├── OmniNodeApp.kt                   # Hilt application container & notification channels
│   │   ├── MainActivity.kt                  # Single Activity hosting Jetpack Compose & permission gate
│   │   ├── AssistantOverlayActivity.kt      # Floating transparent assistant overlay with live transcription
│   │   ├── ai/
│   │   │   ├── NlpParser.kt                 # Rule-based zero-latency semantic intent & device parser
│   │   │   ├── LiteRtManager.kt             # QNN NPU delegate runtime initialization chain
│   │   │   ├── GemmaEngine.kt               # On-device Gemma-4-2B LLM command generation
│   │   │   └── FastVlmEngine.kt             # FastVLM 0.5B visual scene analysis engine
│   │   ├── data/model/
│   │   │   ├── Models.kt                    # SmartDevice, Structure, Trait, and HA Protocol models
│   │   │   └── MoshiExtensions.kt           # JSON serialization adapters
│   │   ├── di/
│   │   │   └── NetworkModule.kt             # Hilt dependency injection for WebSocket & Moshi
│   │   ├── network/
│   │   │   ├── HomeAssistantWsClient.kt     # Resilient OkHttp WebSocket client for Home Assistant
│   │   │   ├── MatterController.kt          # Google Play Services Home / Matter SDK controller
│   │   │   └── VirtualDeviceRegistry.kt     # Offline in-memory fallback state engine
│   │   ├── service/
│   │   │   ├── OmniNodeForegroundService.kt # Persistent IoT daemon & WebSocket watchdog
│   │   │   ├── OmniBackgroundService.kt     # 24/7 ambient wake-word & audio handoff service
│   │   │   └── TtsManager.kt                # Audio-focused Text-to-Speech manager
│   │   ├── ui/
│   │   │   ├── navigation/Navigation.kt     # Compose navigation graph (Dashboard, Devices, Agent, Vision, Settings)
│   │   │   ├── screen/                      # Reactive Jetpack Compose UI screens
│   │   │   └── theme/                       # Modern dark neon-edge design system
│   │   └── viewmodel/                       # StateFlow-driven ViewModels
│   └── res/                                 # Vector drawables, themes, and layout definitions
├── build.gradle.kts                         # Root build configuration
├── settings.gradle.kts                      # Gradle settings & plugin repositories
└── .gitignore                               # Clean Android build artifact exclusion
```

---

## 🛠️ Tech Stack & Dependencies

- **Language:** Kotlin 2.0+ with Coroutines & StateFlow
- **UI Framework:** Jetpack Compose & Material 3
- **Dependency Injection:** Dagger Hilt
- **Wake Word Detection:** Picovoice Porcupine
- **Speech Engine:** Android SpeechRecognizer + TextToSpeech
- **IoT Communication:** OkHttp 4 (WebSockets), Moshi (JSON), Google Home APIs SDK (Matter)
- **Edge AI:** Google LiteRT (TFLite QNN delegate support)

---

## 🚀 Getting Started

### 1. Prerequisites
- Android Studio Ladybug (or newer)
- Android SDK 35 (Minimum SDK: 26)
- Physical Android device or Emulator with microphone support

### 2. Clone & Build
```bash
git clone https://github.com/<your-username>/OmniNode_Hub.git
cd OmniNode_Hub
./gradlew assembleDebug
```

### 3. Device Configuration
1. Install the APK on your device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. Open **OmniNode Hub** and grant the required permissions:
   - **Microphone** (Audio input)
   - **Display over other apps** (Assistant floating overlay)
   - *(Redmi/Xiaomi devices)*: Go to **App Info ➔ Other Permissions** and enable **"Display pop-up windows while running in the background"**.
3. In the app's **Settings** screen, paste your free [Picovoice Access Key](https://console.picovoice.ai/) to enable the "Hey Omni" wake word.

---

## 🗣️ Supported Voice Commands

| Action | Example Spoken Phrase |
| :--- | :--- |
| **Smart Door Lock** | *"Hey Omni, lock the front door."* / *"Unlock the door."* |
| **Lighting** | *"Hey Omni, turn on the living room lights."* / *"Dim the lights to 40 percent."* |
| **Climate & Fans** | *"Hey Omni, set the AC to 22 degrees."* / *"Turn off the fan."* |
| **System Status** | *"Hey Omni, what is the status of the front door?"* |
| **Identity & Help** | *"Hey Omni, what are you?"* / *"Hey Omni, help."* |

---

## 📄 License
This project is open-source under the Apache 2.0 License.
