#!/usr/bin/env bash
# Full build: assets -> plates -> sound mix -> frames -> MP4 (1920x1080, 25 fps, H.264 + AAC).
set -euo pipefail
cd "$(dirname "$0")"
OUT=out/Shell_Erste_25L_15s_1080p25.mp4
tools/fetch_assets.sh
python3 -I tools/plates.py
python3 -I tools/audio.py
rm -rf build/frames
node src/render.mjs --out build/frames --workers 4
mkdir -p out
ffmpeg -nostdin -v error -y -framerate 25 -i build/frames/f_%04d.jpg -i build/mix.wav \
  -filter_complex "[1:a]loudnorm=I=-14:TP=-1.0:LRA=8:linear=true,aresample=48000[a]" \
  -map 0:v -map "[a]" -c:v libx264 -preset slow -crf 15 -profile:v high -pix_fmt yuv420p \
  -x264-params "keyint=25:min-keyint=25" -c:a aac -b:a 320k -ar 48000 -t 15 -movflags +faststart "$OUT"
python3 -I tools/contact_sheet.py "$OUT" out/storyboard.jpg
echo "done -> $OUT"
