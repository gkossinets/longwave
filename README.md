# Longwave

Internet radio and radio alarms for Sonos speakers, without TuneIn.

> Longwave is an unofficial, independent project. It is not affiliated with, endorsed by, or sponsored by Sonos, Inc. "Sonos" is a trademark of Sonos, Inc., used here only to say which speakers this works with. It controls the speakers through their local network interface (UPnP), which Sonos does not officially document for third parties and may change.

> This repo has two parts: the command-line tool described below, and an Android app in [`android/`](android/README.md) with the same features plus station browsing, a control bar, a speaker switcher and a media notification. `soap.sh` is a small curl helper for sending raw Sonos SOAP commands while debugging.

Play web radio on Sonos, and set native Sonos alarms, without TuneIn.

The speakers are told to play a raw stream URL directly over the local network. Alarms are saved on the Sonos system itself, so they fire whether or not this Mac is on. No ads, no TuneIn sign-in to expire.

## Usage

```
./longwave rooms                      # rooms, volume, what's playing
./longwave stations                   # configured stations
./longwave play helsinki -r Bedroom   # play now (optional -v 15 to set volume)
./longwave stop -r Bedroom

./longwave alarm list
./longwave alarm add 07:00 helsinki -r Bedroom --days weekdays -v 15 --duration 60
./longwave alarm add 08:30 helsinki -r Bedroom --days sat,sun
./longwave alarm disable <id>
./longwave alarm enable <id>
./longwave alarm remove <id>
./longwave alarm set-station <id> helsinki   # repoint an existing alarm (e.g. an old TuneIn one)
```

- Room names are case-insensitive. The default room is Bedroom (override with the `LONGWAVE_ROOM` environment variable).
- `--days` accepts `daily`, `weekdays`, `weekends`, `once`, or a list like `mon,wed,fri`.
- Station names match partially, so `play hel` works.

To run it from anywhere, link it onto your PATH, e.g. `ln -s ~/github/longwave/longwave ~/bin/longwave` (if `~/bin` is on your PATH).

## Adding stations

Edit `stations.json`:

```json
{
  "helsinki": { "name": "Radio Helsinki", "url": "https://stream.radiohelsinki.fi/" },
  "fip":      { "name": "FIP", "url": "https://icecast.radiofrance.fr/fip-hifi.aac" }
}
```

The URL must be the audio stream itself, not the station's web page. Good sources: the station's own "listen" link, or radio-browser.info. MP3 and AAC Shoutcast/Icecast streams work; HLS (`.m3u8`) streams may not.

## How it works

- Playback: the stream URL is sent to the room's group coordinator with the `x-rincon-mp3radio://` prefix, which tells Sonos to treat it as an internet radio stream. The `https://` is dropped (Sonos then fetches over http), which is the tested path for Radio Helsinki. A station that only serves https would need the code in `stream_uri()` changed to keep the scheme.
- Alarms: written through the speaker's AlarmClock service (via SoCo), with the stream as the alarm source. If a stream ever fails at alarm time, Sonos falls back to its built-in chime, so you still wake up.

## Caveats

- **Editing an alarm in the Sonos app** may replace the source, since the app can't browse to a raw stream. Manage these alarms with this tool; enabling/disabling in the app should be fine but is untested.
- **The Sonos app may show the alarm source oddly** (title should read "Radio Helsinki").
- **Local Network permission (macOS).** The virtualenv is built on Apple's system Python (`/usr/bin/python3`) because Homebrew's Python was blocked from the local network. The first time you run it from Terminal, macOS may ask to allow Terminal on the local network; say yes.
- **Sonos firmware.** This relies on the speakers' local UPnP control, which Sonos still supports on S2 (tested on 17.2.4, October 2026). A future firmware update could change that.

## Setup notes

- Speakers found (one S2 household, all on 17.2.4): Bathroom (Play:1), Bedroom (Play:3), Kitchen, Living Room (stereo pair of Play:1s).
- Rebuild the environment if needed: `/usr/bin/python3 -m venv .venv && .venv/bin/pip install soco`

## Tested (2026-10-25)

- Direct playback on Bathroom: plays.
- One-time alarm on Bathroom with the stream as source: fired on time and played the stream.
- `alarm add` / `disable` / `remove` round-trip via the CLI.
