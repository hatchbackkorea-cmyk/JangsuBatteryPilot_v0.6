# v0.30.5 validation

- Manual ROAD fallback requires FTP 50~700W + body weight 30~200kg.
- W/kg is stored in ROAD profile metadata and shown in profile summary.
- Fallback climb model uses W/kg when body weight exists.
- Finish-plan panel always computes an unscaled predicted finish time first.
- Goal-time plan is a separate explicit action below predicted finish time.
- Goal plan shows target average speed and delta from predicted finish.
- Race simulator FTP-only rider now requires body weight and displays W/kg.
- Auto simulation playback remains ~50 seconds; no manual speed controls.
