#!/usr/bin/env bash
# Downloads the royalty-free stock footage, music and SFX used by the spot.
# Mixkit (Mixkit Free License) and Coverr (Coverr License) — free for commercial use, no attribution.
# Raw stock is not committed to the repo (licences forbid redistributing it as stock).
set -euo pipefail
cd "$(dirname "$0")/../assets"
mk()  { [ -s "stock/$2" ] || curl -fsSL -m 300 -o "stock/$2" "https://assets.mixkit.co/videos/$1/$1-720.mp4"; }
cv()  { [ -s "stock/$2" ] || curl -fsSL -m 300 -o "stock/$2" "https://cdn.coverr.co/videos/$1/1080p.mp4"; }
mus() { [ -s "music/$2" ] || curl -fsSL -m 300 -o "music/$2" "https://assets.mixkit.co/music/$1/$1.mp3"; }
sfx() { [ -s "sfx/$2" ]   || curl -fsSL -m 120 -o "sfx/$2"   "https://assets.mixkit.co/active_storage/sfx/$1/$1-preview.mp3"; }
mkdir -p stock music sfx
mk 7829  lake_sunset_drone.mp4        # Flying over a lake at sunset
mk 35261 suv_sunset_tracking.mp4      # SUV in a road at sunset, tracking shot
mk 31961 fuel_nozzle_hand.mp4         # Man's hand taking the fuel nozzle at the gas station
cv coverr-woman-approaching-parked-car woman_gets_in_car.mp4
mus 470 golden_storm.mp3              # "Golden Storm" by Diego Nava
sfx 1492 whoosh_cinematic.mp3
sfx 1489 whoosh_air.mp3
sfx 1486 whoosh_tunnel.mp3
sfx 3129 car_door_close.mp3
sfx 1564 car_door_slam.mp3
sfx 1566 car_engine_start.mp3
sfx 2867 confirmation_tone.mp3
sfx 2870 correct_tone.mp3
sfx 221  interface_beep.mp3
sfx 3124 tech_select.mp3
sfx 2585 tap_click.mp3
echo "assets ready"
