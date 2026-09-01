# DT Monitor

Lightweight Android app for monitoring OwO Distorted Time (DT) windows without a Discord bot token.

## Features
- Manual target list: `Name,Discord Server ID` per line
- Persistent local configuration
- Manual **Re-check Now**
- Background checks requested every 10 minutes (Android may defer them)
- Persistent duplicate-alert protection based on the OwO shard restart event
- Discord webhook alerts
- No Discord token, Discord SDK, or third-party networking library
- Minimal native Android UI

## Build
Use GitHub Actions (`.github/workflows/build.yml`) to build a debug APK.

## Target format
```text
Server One,123456789012345678
Server Two,987654321098765432
```
