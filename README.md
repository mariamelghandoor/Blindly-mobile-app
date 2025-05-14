# Blindly - Android Navigation Assistant for Visually Impaired

## 📌 Overview

Blindly is an Android application designed to assist visually impaired individuals in navigating indoor spaces by:

* Detecting doors and their components in real-time
* Identifying potential obstacles
* Providing audio feedback about the environment

## ✨ Key Features

* **Smart Object Detection**
  - Door detection with component recognition (knobs, levers)
  - Obstacle detection for common items (furniture, people, etc.)
  - Depth estimation for distance awareness

* **Audio Guidance**
  - Spoken directions about door locations ("Door detected on your left")
  - Obstacle warnings ("Chair detected 2 meters ahead")
  - Safety alerts ("Stop! Wall very close")

* **Multiple Operation Modes**
  - 📸 Manual capture mode
  - 🔄 Auto-capture every 5 seconds
  - ⏸️ Camera stream control

## 🛠️ Technical Stack

* **Computer Vision**
  - TensorFlow Lite models:
    - YOLOv8 (obstacle detection)
    - Custom YOLO (door detection)
    - MiDaS (depth estimation)

* **Android Components**
  - CameraX API
  - Jetpack Compose UI
  - TextToSpeech engine

## ⚙ Installation

1. Clone the repository:
   
   ```bash
   git clone hhttps://github.com/mariamelghandoor/Door-path-helper-mobile-app.git

2. Open project in Android Studio

3. Sync Gradle dependencies

4. Connect Android device or use emulator (API 24+)

5. Click Run (▶️ button) to build and install



### To generate a new APK:

1. In Android Studio:

    Go to Build → Build Bundle(s)/APK(s) → Build APK(s)

## Locating the APK

After successful build, the debug APK can be found at:

  ```bash
  app/build/outputs/apk/debug/app-debug.apk
  ```
## 🚀 Usage Guide

### Initial Setup
1. **Permission Granting**  
   - On first launch, the app will request camera permissions
   - Tap "Allow" to enable all features
   - *(If denied accidentally, go to Settings → Apps → Blindly → Permissions to enable)*

### Interface Controls
| Button | Icon | Functionality | Best Use Case |
|--------|------|--------------|---------------|
| **Manual Capture** | 📸 | Takes single photo for immediate analysis | When you need specific environment scan |
| **Auto Capture** | 🔄 | Toggles automatic photos every 5 seconds | Continuous navigation assistance |
| **Stream Toggle** | ⏸️/▶️ | Starts/stops camera preview | Battery conservation when not actively navigating |

### Usage Scenarios
#### 🚪 Finding Doors
1. Point your phone at potential door areas
2. Listen for audio cues:
   - "Door detected ahead" (centered in view)
   - "Door on your left/right" (off-center doors)
   - "Door knob detected" (when close to handle)

#### ⚠️ Obstacle Avoidance  
The app automatically warns about:
- Immediate hazards ("Stop! Wall very close")
- Nearby objects ("Table 2 meters ahead")
- Moving obstacles ("Person approaching from left")

#### Battery Saving Tips
- Use **Stream Toggle** when not actively navigating
- Disable **Auto Capture** when stationary
- Lower screen brightness in settings

### Voice Command Reference
| Audio Alert | Meaning | Recommended Action |
|-------------|---------|---------------------|
| "Door detected [location]" | Door visible in specified direction | Move toward door while listening for handle cues |
| "[Object] detected [distance]" | Obstacle ahead | Slow down and verify with manual scan |
| "Detection failed" | Analysis error | Reposition phone and try manual capture |


# Code Structure

  ```bash
app/
└── src/
    ├── main/
    │   ├── assets/               # ML models
    │   ├── java/com/example/blindly/
    │   │   ├── MainActivity.kt      # Core logic
    │   │   ├── MidasTFLiteHelper.kt # Depth estimation
    │   │   └── YoloTFLiteHelper.kt  # Object detection
    │   └── res/                  # Resources
  ```


# Requirements
* Android 7.0+ (API 24)

* Camera with autofocus

* ARM64 or ARMv7 processor

* Minimum 2GB RAM recommended

# License
Distributed under the MIT License. 
