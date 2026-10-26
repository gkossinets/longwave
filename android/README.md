# Longwave (Android)

*Unofficial; not affiliated with or endorsed by Sonos, Inc. See the note in the [main README](../README.md).*

A personal Android app for playing internet radio on Sonos speakers and for setting Sonos alarms that wake you with a radio stream, without TuneIn.

It talks to the speakers directly over the home Wi-Fi (UPnP/SOAP on port 1400), using the same approach as the `longwave` command-line tool in the parent folder. Alarms are saved on the Sonos system itself, so they fire whether or not the phone is home or switched on.

## Features

- **My stations**: your saved list (pre-seeded with Radio Helsinki). Edit, reorder, remove, or open a station's website.
- **Browse**
  - *Curated*: about 455 stations from [deroverda/recommended-radio-streams](https://github.com/deroverda/recommended-radio-streams) (CC0), bundled with the app; filter by genre or text.
  - *Worldwide*: search the open [radio-browser.info](https://www.radio-browser.info) directory by name, country code and genre tag (an empty search shows the most-voted stations).
  - *Add URL*: paste a stream link (for example from fmstream.org) and check that it's really an audio stream before adding it. You can also share a link from the browser to the app (Share → Longwave).
- **Play on…**: pick one or more rooms; the first one leads the group and the others join it. Rooms grouped with the leader but not selected are split off. Optionally set the volume.
- **Alarms**: list, create, edit, enable/disable and delete Sonos alarms. One alarm can cover several rooms (it is stored as one Sonos alarm per room and shown as a single card). The sound can be any station in My stations or the Sonos chime. Alarms made in the Sonos app (e.g. old TuneIn ones) are shown too; editing them here keeps their source unless you pick another.
- **Speakers**: what each room is playing, volume, stop, ungroup.
- **Control bar** (above the tabs, on every screen): what the *active* group is playing, with play/stop. Tap it to pick which room or group is active and to set the group volume. The speaker-group button next to play/stop opens a checkbox list of all rooms; each tap regroups immediately so the checked rooms play together. Unchecking the room that leads the group starts the same stream on a checked room, moves the other checked rooms to it and stops the old leader, so the music carries on after a second or two. (`DelegateGroupCoordinationTo` would avoid the gap, but when the old leader is a stereo pair it leaves the pair's hidden right speaker playing; it is used only for Sonos-queue content, which can't be copied.) The active group is the one you last played to or picked; otherwise the first group that is playing.
- **Media notification**: the active group also appears in the notification shade and on the lock screen (play/stop, title, rooms; volume via the system media controls). This is a Media3 `MediaSessionService` (`PlaybackService.kt`) whose "player" (`SonosPlayer`) plays nothing on the phone; it mirrors the Sonos group's state, polled every 5 s while the service runs, and forwards commands to the speakers. The service stays alive while the group is playing, even with the app closed, and stops when playback is stopped and the app is gone.

## Building and installing

Requirements: Android Studio (it provides the SDK and a JDK). The project uses AGP 9.4 (built-in Kotlin), Gradle 9.8, Kotlin 2.4, Jetpack Compose (BOM 2026.09), compileSdk 37, minSdk 26 (Android 8), targetSdk 35. Android Studio 2026.2's bundled JDK 25 needs Gradle 9.x.

- In Android Studio: *Open* this `android` folder, wait for the Gradle sync, plug in the phone (USB debugging on) and press Run.
- From the terminal (Studio's bundled JDK):

  ```
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

## Code map

| File | What it does |
|---|---|
| `sonos/Sonos.kt` | SOAP calls, speaker discovery (cached IPs, then SSDP multicast, then a port-1400 scan of the /24), group topology, play/group/stop/volume, alarm list/create/update/destroy, recurrence helpers |
| `data/Stations.kt` | Station model, My-stations storage (JSON in app files), curated asset loader, Radio Browser client, stream checker |
| `AppViewModel.kt` | App state and actions; groups per-room Sonos alarms into one card |
| `sonos/NowPlaying.kt` | Shared state of the active group (used by the control bar and the notification); play/stop, group volume, polling |
| `data/Artwork.kt` | Notification/control-bar artwork: station logo when one of at least 96 px can be found (Radio Browser favicon, homepage icons, og:image), else a generated tile with the station name; cached 30 days |
| `SonosRouteProvider.kt` | Registers each Sonos room as an Android media output and the active group as a routing session, so the media card's output chip shows the room and its sheet can move, group, stop and set volume. `PlaybackService` transfers the app's media to the playing room so Android creates the session; the media session links to it through `DeviceInfo.routingControllerId` |
| `PlaybackService.kt` | Media session service and the `SonosPlayer` adapter behind the notification |
| `ui/MiniPlayer.kt` | Control bar and its room/volume sheet |
| `MainActivity.kt` | Theme, bottom navigation, share-intent handling |
| `ui/Screens.kt` | My stations, Play sheet, Browse tabs, Speakers |
| `ui/AlarmsScreen.kt` | Alarm list and editor |
| `assets/curated_stations.json` | Generated from the deroverda README (see below) |

## Technical notes (tested on Sonos S2, October 2026)

- A stream plays when its URL is given to Sonos as `x-rincon-mp3radio://<full URL>`. Keeping `https://` works; MP3, AAC, `.pls` playlists and HLS (`.m3u8`) all reached the playing state.
- Grouping: `SetAVTransportURI` with `x-rincon:<leader UUID>` on each joining room; `BecomeCoordinatorOfStandaloneGroup` to leave.
- The second speaker of a stereo pair is marked `Invisible="1"` in the topology and is skipped; the pair appears as one room.
- Alarm sound for a stream: `ProgramURI = x-rincon-mp3radio://…` with a DIDL `audioBroadcast` title. Chime: `x-rincon-buzzer:0`.
- Sonos refuses two alarms in the same room at the same start time.

## Caveats

- **Editing these alarms in the Sonos app** may replace the stream with something else, because the Sonos app can't browse to a raw URL. Use this app for alarms with a stream.
- **Same Wi-Fi only.** The phone has to be on the home network.
- **Firmware dependency.** This relies on the speakers' local UPnP control, which Sonos S2 still supports (tested on S2 software 17.2). A future firmware update could change that.

## Regenerating the curated list

```
curl -sL https://raw.githubusercontent.com/deroverda/recommended-radio-streams/main/README.md -o /tmp/rrs.md
python3 tools/parse_curated.py /tmp/rrs.md app/src/main/assets/curated_stations.json
```

The script takes every bullet in "The Station Directory" and "Experimental" sections that ends in stream links, skips entries marked *(down)*, splits multi-channel entries into one station per channel, and de-duplicates by URL.
