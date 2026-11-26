#!/usr/bin/env python3
"""longwave: play web radio streams on Sonos and set native Sonos alarms,
without TuneIn.

Speakers are controlled over the local network (UPnP/SOAP via SoCo). Alarms are
saved on the Sonos system itself, so they fire without this computer being on.

See README.md for usage.
"""
import argparse
import datetime
import json
import os
import sys
import warnings
from xml.sax.saxutils import escape

warnings.filterwarnings("ignore")  # urllib3 LibreSSL warning on macOS system Python

import soco  # noqa: E402
from soco.alarms import Alarm, get_alarms  # noqa: E402

HERE = os.path.dirname(os.path.realpath(__file__))
STATIONS_FILE = os.path.join(HERE, "stations.json")
DEFAULT_ROOM = os.environ.get("LONGWAVE_ROOM", "")

DAY_CODES = {"sun": "0", "mon": "1", "tue": "2", "wed": "3", "thu": "4", "fri": "5", "sat": "6"}
DAY_NAMES = {v: k.capitalize() for k, v in DAY_CODES.items()}


# ---------- stations ----------

def load_stations():
    with open(STATIONS_FILE) as f:
        return json.load(f)


def get_station(key):
    stations = load_stations()
    if key in stations:
        return key, stations[key]
    matches = [k for k, s in stations.items() if key.lower() in (k + " " + s["name"]).lower()]
    if len(matches) == 1:
        return matches[0], stations[matches[0]]
    sys.exit("Unknown station '%s'. Known: %s" % (key, ", ".join(sorted(stations))))


def stream_uri(url):
    """Sonos plays a raw stream when the URI carries the x-rincon-mp3radio prefix.
    The scheme is dropped (Sonos then uses http), which is the tested path."""
    for scheme in ("https://", "http://"):
        if url.startswith(scheme):
            url = url[len(scheme):]
    return "x-rincon-mp3radio://" + url


def didl(title):
    return (
        '<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" '
        'xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" '
        'xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">'
        '<item id="R:0/0/0" parentID="R:0/0" restricted="true">'
        "<dc:title>%s</dc:title>"
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>"
        '<desc id="cdudn" nameSpace="urn:schemas-rinconnetworks-com:metadata-1-0/">'
        "SA_RINCON65031_</desc></item></DIDL-Lite>" % escape(title)
    )


# ---------- rooms ----------

def zones():
    found = soco.discover(timeout=5) or soco.discover(timeout=5, allow_network_scan=True)
    if not found:
        sys.exit("No Sonos speakers found. Is this computer on the home network, and does the "
                 "app running this (e.g. Terminal) have Local Network permission?")
    return sorted(found, key=lambda z: z.player_name)


def find_room(name):
    if not name:
        zs = zones()
        if len(zs) == 1:
            return zs[0].group.coordinator if zs[0].group else zs[0]
        sys.exit("Which room? Use -r ROOM or set LONGWAVE_ROOM. Rooms: %s" % ", ".join(z.player_name for z in zs))
    for z in zones():
        if z.player_name.lower() == name.lower():
            return z.group.coordinator if z.group else z
    sys.exit("No room named '%s'. Rooms: %s" % (name, ", ".join(z.player_name for z in zones())))


# ---------- commands ----------

def cmd_rooms(_args):
    for z in zones():
        st = z.group.coordinator.get_current_transport_info()["current_transport_state"]
        media = z.group.coordinator.get_current_media_info().get("channel") or ""
        print("%-14s %-15s vol %3d  %-16s %s" % (z.player_name, z.ip_address, z.volume, st, media))


def cmd_stations(_args):
    for k, s in sorted(load_stations().items()):
        print("%-12s %-24s %s" % (k, s["name"], s["url"]))


def cmd_play(args):
    _, st = get_station(args.station)
    z = find_room(args.room)
    if args.volume is not None:
        z.volume = args.volume
    z.play_uri(stream_uri(st["url"]), meta=didl(st["name"]), title=st["name"])
    print("Playing %s in %s (volume %d)" % (st["name"], z.player_name, z.volume))


def cmd_stop(args):
    z = find_room(args.room)
    z.stop()
    print("Stopped %s" % z.player_name)


def parse_days(spec):
    s = spec.lower()
    if s in ("daily", "weekdays", "weekends", "once"):
        return s.upper()
    codes = []
    for d in s.split(","):
        d = d.strip()[:3]
        if d not in DAY_CODES:
            sys.exit("Bad day '%s'. Use daily, weekdays, weekends, once, or e.g. mon,wed,fri" % d)
        codes.append(DAY_CODES[d])
    return "ON_" + "".join(sorted(set(codes)))


def describe_recurrence(r):
    if r.startswith("ON_"):
        return ",".join(DAY_NAMES[c] for c in r[3:])
    return r.capitalize()


def describe_source(a, stations):
    uri = a.program_uri or ""
    for k, s in stations.items():
        if uri == stream_uri(s["url"]):
            return "%s (direct stream)" % s["name"]
    if "sid=333" in uri or "sid=254" in uri:
        return "TuneIn: " + uri[:50]
    if not uri:
        return "Sonos chime"
    return uri[:60]


def cmd_alarm_list(_args):
    stations = load_stations()
    alarms = sorted(get_alarms(zones()[0]), key=lambda a: (a.zone.player_name, a.start_time))
    if not alarms:
        print("No alarms.")
    for a in alarms:
        print("%-5s %-12s %s  %-14s %-3s vol %-3d %s" % (
            a.alarm_id, a.zone.player_name, a.start_time.strftime("%H:%M"),
            describe_recurrence(a.recurrence), "on" if a.enabled else "off",
            a.volume, describe_source(a, stations)))


def cmd_alarm_add(args):
    _, st = get_station(args.station)
    z = find_room(args.room)
    try:
        start = datetime.datetime.strptime(args.time, "%H:%M").time()
    except ValueError:
        sys.exit("Time must be HH:MM (24-hour), e.g. 07:00")
    dur = datetime.time(args.duration // 60, args.duration % 60, 0)
    a = Alarm(z, start_time=start, duration=dur, recurrence=parse_days(args.days),
              enabled=True, program_uri=stream_uri(st["url"]), program_metadata=didl(st["name"]),
              play_mode="NORMAL", volume=args.volume, include_linked_zones=args.group)
    a.save()
    print("Alarm %s: %s in %s at %s, %s, volume %d, plays %d min" % (
        a.alarm_id, st["name"], z.player_name, args.time,
        describe_recurrence(a.recurrence), args.volume, args.duration))


def _get_alarm(alarm_id):
    for a in get_alarms(zones()[0]):
        if a.alarm_id == str(alarm_id):
            return a
    sys.exit("No alarm with id %s (see: longwave alarm list)" % alarm_id)


def cmd_alarm_remove(args):
    a = _get_alarm(args.id)
    a.remove()
    print("Removed alarm %s" % args.id)


def cmd_alarm_toggle(args, enabled):
    a = _get_alarm(args.id)
    a.enabled = enabled
    a.save()
    print("Alarm %s %s" % (args.id, "enabled" if enabled else "disabled"))


def cmd_alarm_station(args):
    """Repoint an existing alarm (e.g. an old TuneIn one) at a direct stream."""
    _, st = get_station(args.station)
    a = _get_alarm(args.id)
    a.program_uri = stream_uri(st["url"])
    a.program_metadata = didl(st["name"])
    a.play_mode = "NORMAL"
    a.save()
    print("Alarm %s now plays %s" % (args.id, st["name"]))


def main():
    p = argparse.ArgumentParser(prog="longwave", description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("rooms", help="list rooms and what they're doing").set_defaults(fn=cmd_rooms)
    sub.add_parser("stations", help="list configured stations").set_defaults(fn=cmd_stations)

    sp = sub.add_parser("play", help="play a station now")
    sp.add_argument("station")
    sp.add_argument("--room", "-r", default=DEFAULT_ROOM)
    sp.add_argument("--volume", "-v", type=int)
    sp.set_defaults(fn=cmd_play)

    sp = sub.add_parser("stop", help="stop a room")
    sp.add_argument("--room", "-r", default=DEFAULT_ROOM)
    sp.set_defaults(fn=cmd_stop)

    al = sub.add_parser("alarm", help="manage native Sonos alarms").add_subparsers(dest="acmd", required=True)
    al.add_parser("list").set_defaults(fn=cmd_alarm_list)
    sp = al.add_parser("add", help="e.g. alarm add 07:00 helsinki --days weekdays")
    sp.add_argument("time", help="HH:MM, 24-hour")
    sp.add_argument("station")
    sp.add_argument("--room", "-r", default=DEFAULT_ROOM)
    sp.add_argument("--days", "-d", default="weekdays",
                    help="daily | weekdays | weekends | once | mon,tue,... (default weekdays)")
    sp.add_argument("--volume", "-v", type=int, default=15)
    sp.add_argument("--duration", type=int, default=60, help="minutes to play (default 60)")
    sp.add_argument("--group", action="store_true", help="also play on rooms grouped with this one")
    sp.set_defaults(fn=cmd_alarm_add)
    for name, fn in (("remove", cmd_alarm_remove),
                     ("enable", lambda a: cmd_alarm_toggle(a, True)),
                     ("disable", lambda a: cmd_alarm_toggle(a, False))):
        sp = al.add_parser(name)
        sp.add_argument("id")
        sp.set_defaults(fn=fn)
    sp = al.add_parser("set-station", help="repoint an existing alarm at a station")
    sp.add_argument("id")
    sp.add_argument("station")
    sp.set_defaults(fn=cmd_alarm_station)

    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
