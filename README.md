# NovaVPN — Xray / WireGuard Android project

This project is a source project, not a prebuilt or device-tested APK.

## Build with GitHub Actions

1. Upload the contents of this folder to the root of your `Nova-VPN` repository.
2. Keep the repository default branch as `main`.
3. Open **Actions → NovaVPN Android APK → Run workflow**.
4. When the run succeeds, open the run and download **NovaVPN-Xray-debug-apk**.

The workflow initializes JDK before Android SDK tools, installs Android platform 35
and NDK 27.2.12479018, builds the official libXray AAR, then invokes Gradle.

## Important limitations

- A successful APK build proves compilation only. It does not prove that a VPN
  connection works against a real server.
- A valid server profile and a compatible server are required.
- The app's Xray TUN integration and profile conversion must be tested on an
  Android device against real VMess/VLESS/etc. server configurations.
- The workflow pins the libXray source commit supplied with the previous project.
  If that upstream commit or its toolchain is unavailable, the Xray AAR step can
  still fail and the log will identify the failing command.
- Do not share private server links, UUIDs, passwords, or keys publicly.

## Project structure

- `app/` — Android app source
- `app/libs/` — generated libXray AAR is copied here during CI; it is not bundled
  into this ZIP
- `.github/workflows/android.yml` — GitHub Actions build workflow


## UI motion
The home screen includes a gentle breathing animation on the disconnected Connect control and an in-button progress indicator while a connection action is running. Motion is visual feedback only; it is not proof of VPN connectivity.
