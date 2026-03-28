# DJI Flight Simulator

A lightweight 2D drone flight simulator for Android, built with Kotlin and Jetpack Compose.

## Features

- **2D Top-down flight view** with camera following the drone
- **Realistic physics engine** with gravity, drag, thrust, and tilt-based movement
- **Virtual dual joysticks** (Mode 2 control layout):
  - Left stick: Throttle (up/down) + Yaw (rotate)
  - Right stick: Pitch (forward/back) + Roll (left/right)
- **HUD overlay** showing altitude, speed, heading, battery, flight time
- **Minimap** with distance limit ring
- **Collision detection** with buildings, trees, and towers
- **Two flight modes**:
  - Free Flight: explore the map freely
  - Training: follow waypoints to practice navigation
- **Flight trail** visualization
- **Battery simulation** with low-battery warnings

## Building

Open in Android Studio and run on an emulator or device (API 26+).

```bash
cd dji-flight-sim
./gradlew assembleDebug
```

## Project Structure

```
app/src/main/java/com/dji/flightsim/
├── MainActivity.kt              # Entry point and navigation
├── engine/
│   ├── FlightPhysicsEngine.kt   # Drone physics simulation
│   └── TerrainMap.kt            # Obstacles, waypoints, collision
└── ui/
    ├── theme/Colors.kt          # DJI-style color palette
    ├── components/
    │   ├── VirtualJoystick.kt   # Touch joystick control
    │   ├── FlightMapView.kt     # 2D map rendering (Canvas)
    │   └── HudOverlay.kt       # Telemetry HUD display
    └── screens/
        ├── MainMenuScreen.kt    # Main menu
        └── FlightScreen.kt     # Flight simulation screen
```
