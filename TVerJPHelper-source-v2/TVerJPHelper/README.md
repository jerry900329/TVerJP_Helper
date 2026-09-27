# TVer JP Helper

A tiny Android helper for Samsung Galaxy S25 Ultra / modern Android:

1. Fetches the University of Tsukuba VPN Gate live CSV API.
2. Keeps only Japan (`JP`) OpenVPN servers.
3. Filters/ranks by official ping, line speed, uptime, and session count.
4. Measures TCP endpoint latency from the phone when possible.
5. Uses OpenVPN for Android's documented AIDL external API to start the inline configuration.
6. Automatically retries the next ranked server if the first connection times out.
7. Launches TVer after OpenVPN reports `CONNECTED`.

## Required app

Install **OpenVPN for Android** by Arne Schwabe (`de.blinkt.openvpn`) from Google Play or F-Droid.
Do not use OpenVPN Connect for this helper; this project targets OpenVPN for Android's AIDL API.

## First run

Android/OpenVPN for Android will show two one-time permission prompts:

- allow TVer JP to control OpenVPN for Android;
- allow the VPN connection.

After that, tapping TVer JP should automatically select a current Japanese VPN Gate node and launch TVer.

## Build

Open this folder in a recent Android Studio. The project uses Java 17, AGP 8.13.2, compileSdk 36, and no third-party runtime libraries.
Build > Build APK(s), then sideload the APK to the phone.

## Notes

VPN Gate servers are volunteer-operated and can disappear at any time. The helper intentionally downloads the live list on each run and retries other candidates.
The VPN Gate CSV `Speed` and `Ping` are server-list measurements, not guaranteed end-to-end throughput from your phone. This helper therefore combines those fields with a direct TCP endpoint latency check when the returned OpenVPN profile uses TCP.

TVer may reject some VPN/proxy IP addresses. A successful VPN connection does not guarantee TVer accepts that particular exit IP.

## Attribution

The AIDL interface and `APIVpnProfile` compatibility class are based on the OpenVPN for Android `remoteExample`, which its author documents as Apache-2.0 licensed / exempt from the main GPL for external API use.

## Build with GitHub Actions

This repository includes `.github/workflows/build-apk.yml`.
Push it to GitHub, open **Actions > Build APK > Run workflow**, then download the `TVerJP-debug-apk` artifact.
The generated `app-debug.apk` is debug-signed and can be sideloaded directly on Android.
