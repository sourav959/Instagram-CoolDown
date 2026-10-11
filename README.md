# Insta Cooldown

Insta Cooldown is an Android application designed to help you manage and limit your Instagram usage. It runs in the background to monitor your activity, warns you when you exceed your set limit, and enforces a strict cooldown period to build healthier digital habits.

## Features

- **Live Tracking & Notification:** Continuously tracks your Instagram usage time and displays a live timer directly in a foreground notification (e.g., "1:15 passed out of 5:00").
- **Customizable Limits:** Set your own custom Usage Limit (minutes) and Cooldown Limit (minutes) directly from the app's main setup screen.
- **Graceful Warnings:** Warns you when you hit your time limit with a 5-second countdown overlay before closing the app. 
- **One-Time Extension:** When the warning pops up, you can optionally extend your time for exactly 30 seconds to wrap up what you were doing. This extension is strictly limited to once per session.
- **Strict Cooldown Enforcement:** Once your time limit (or extension) is fully exhausted, the app places Instagram on a strict cooldown.
- **Home Screen Kickout:** If you try to open Instagram while on a cooldown, you will be instantly kicked back to your home screen with a toast message showing exactly how much time is left on your cooldown.

## How It Works

The app utilizes Android's `UsageStatsManager` to monitor foreground activity accurately, accounting for pauses and background switches. It operates via a Foreground Service to ensure it stays active and uses the `SYSTEM_ALERT_WINDOW` permission to display the warning overlay.

