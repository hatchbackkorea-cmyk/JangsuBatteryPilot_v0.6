# RACE event model

RACE does not use the group-room workflow. The event code itself is the participant namespace and live-broadcast channel.

Participant flow:
1. Open RACE MODE from the launcher.
2. Enter name and nickname and press Profile Save.
3. Enter/scan the event code.
4. Join the event.
5. If the event GPX is not already available through the saved event mapping, the Android client downloads it and imports it through `CourseRepository`, which persists GPX files in the app's normal `filesDir/courses` course store.
6. START arms timing; the START gate begins the official run.

The event owns participants, runs, sectors, live state, leaderboards and broadcast data. A separate group room is not required for RACE.
