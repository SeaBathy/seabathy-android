# Publishing SeaBathy on Google Play

Everything Play Console asks for, with the answers for this app. The bundle to upload is
built with `./gradlew bundlePlayRelease` (see [BUILD_AND_TEST.md](BUILD_AND_TEST.md)):
`app/build/outputs/bundle/playRelease/app-play-release.aab`, application id
`com.seabathy.app`, targeting Android 16 (API 36).

The listing pictures are in `SeaBathy-Release/play-store-listing/` (not in this repository):
`icon-512.png`, `feature-graphic-1024x500.png` and `phone-screenshots/` (five, 720 × 1440).

## 1. Before the first upload

- **Developer account:** a personal account is fine. Google shows the developer name you
  choose (for example "SeaBathy") and a contact email on the listing. Use an address made for
  SeaBathy rather than your personal one.
- **Closed test first:** new personal accounts must run a closed test with at least
  **12 testers for 14 days in a row** before production is unlocked. Create the closed track,
  upload the bundle, add the testers' Google accounts, and keep them opted in for the 14 days.
- **Play App Signing:** accept it when asked. Google keeps the app signing key; the key in
  `SeaBathy-Release/play-signing/` is only your **upload key**. Back that folder up somewhere
  safe and private. If it's ever lost, Play Console can reset the upload key.

## 2. Store listing

| Field | Text |
| --- | --- |
| App name (30) | `SeaBathy: 3D Seabed Maps` |
| Short description (80) | `Free 3D seabed maps: your own depth surveys, offline maps, tides and conditions.` |
| Category | Maps & Navigation |
| Website | https://seabathy.com |
| Privacy policy | https://seabathy.com/privacy |

**Full description:**

```
See the seafloor in 3D. SeaBathy opens bathymetry for boaties, anglers and divers, free,
with no account, no ads and no tracking.

• Your own surveys: open any GeoTIFF depth survey or MBTiles chart, at any resolution,
  including multi-gigabyte BigTIFFs. Coloured by depth with relief shading, and the depth
  under the crosshair as you pan.
• 3D seabed: fly over any survey in 3D, stretch the relief up to 100×, add contours, and
  drape Sentinel-2 satellite imagery over it.
• Free seabed depths anywhere in the world (GMRT), and up to 10 m detail around Australia
  with the free BathyPicker tool.
• Offline maps: save the area you need before you leave the ramp.
• Tides and sea conditions: tide charts, wind, waves, swell, currents, sea temperature and
  its breaks, chlorophyll, sea level and salinity, saved for offline use.
• Waypoints, routes and tracks, with GPX import and export and depth checks along every leg.
• Anchor alarm that sounds if your anchor drags.

Open source (MIT): https://github.com/SeaBathy/seabathy-android

NOT FOR NAVIGATION. SeaBathy is for planning and interest. It is not an official nautical
chart. Always carry official charts and proper navigation equipment.
```

Keep other companies' product names (chart brands, plotter makers) out of the name, short
description and tags.

## 3. App content (Policy > App content)

| Item | Answer |
| --- | --- |
| Privacy policy | https://seabathy.com/privacy |
| Ads | No, the app contains no ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Fill in the questionnaire (category: Reference / Utility). Answer **No** to violence, sexual content, language, controlled substances, gambling, user interaction, sharing location with other users and purchases. Expected result: Everyone / PEGI 3 |
| Target audience | 18 and over (keeps the app out of the Families programme; it isn't made for children) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data safety | See section 4 |

## 4. Data safety

SeaBathy has no servers and sends nothing to its developer. The only user data that leaves the
phone is a map position, when the user asks for tides, conditions or a place name there (see
the [privacy policy](https://seabathy.com/privacy)).

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** (location, below) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (all built-in services use HTTPS) |
| Do you provide a way for users to request that their data is deleted? | **No**: the developer stores no user data; everything is on the device and deleted with the app |

Data types: tick **Location → Precise location** only (the app sends coordinates to about
10 m, which counts as precise). For it:

| Question | Answer |
| --- | --- |
| Collected | **Yes** (sent to the tide and forecast services when the user asks for them) |
| Shared | **No** (sent only on the user's own request, to get what they asked for) |
| Processed ephemerally | **Yes** (the developer never stores it) |
| Required or optional | **Optional** (users can use the app without it) |
| Why is it collected | **App functionality** |

Nothing else is collected: no personal info, files, photos, messages, contacts, app activity,
diagnostics, device IDs or financial info. The debug log only leaves the phone if the user
shares it themselves from Settings.

## 5. Permission declarations

Play Console asks about these when the bundle is uploaded.

| Permission | Declaration |
| --- | --- |
| `FOREGROUND_SERVICE_LOCATION` | Two location services: **track recording** and the **anchor drag alarm**. Both are started by the user from the app, run only while the user wants them, show a notification with a Stop button, and need the position while the screen is off. Pick the closest task type (navigation / location tracking the user started) and give a link to a short screen recording showing a track recording and the anchor alarm being started and stopped. |
| `FOREGROUND_SERVICE_DATA_SYNC` | **Downloading an offline map** the user asked for (the area on screen), with a progress notification and a Cancel button. Add a short screen recording of starting an offline download. |
| `USE_FULL_SCREEN_INTENT` | Core function: **alarm**. The anchor drag alarm must wake the screen and sound when the boat drags its anchor, often at night. If Play doesn't grant it by default, the alarm still sounds and shows a high-priority notification. |
| Location (foreground) | No declaration needed: the app does not request background location. |

The app asks for **no storage permissions**.

## 6. Release

1. Upload `app-play-release.aab` to the closed test track (later, production).
2. Release name: `1.1.0`. Release notes:

   ```
   First release on Google Play. 3D seabed maps for your own depth surveys, offline maps,
   tides and sea conditions, waypoints, routes and an anchor alarm. Free, no account, no ads.
   Not for navigation.
   ```

3. After the closed test's 14 days, apply for production access, then promote the release.

For each later update, raise `versionCode` in `app/build.gradle.kts`, rebuild with
`./gradlew bundlePlayRelease` and upload the new bundle.
