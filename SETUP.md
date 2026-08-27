# Getting a real .apk installed on your tablet - no software to install on your PC

You only need: a free GitHub account and a browser. The actual compiling
happens on GitHub's servers, not your computer. The speech model is
downloaded automatically during that build too - you don't need to add
anything to the project yourself.

## 1. Put this project on GitHub

1. Go to github.com, sign up if you don't have an account (free).
2. Click the **+** in the top right -> **New repository**. Name it
   anything (e.g. `car-voice-player`). Leave it Public. Create it.
3. On the new repo's page, click **uploading an existing file**.
4. Drag the entire contents of this project folder (everything inside
   `CarVoicePlayer/`, including the hidden `.github` folder) into the
   browser upload box. If your browser hides the `.github` folder from
   drag-and-drop, use GitHub Desktop instead (also free, one install) or
   ask me and I'll walk through that path.
5. Scroll down, click **Commit changes**.

## 2. Let it build

1. Click the **Actions** tab at the top of your repo.
2. You should see a workflow run start automatically (triggered by your
   upload). If not, click **Build APK** on the left, then **Run workflow**.
3. Wait a few minutes. Green check = success. Red X = something needs
   fixing - click into it, copy me the error text, and I'll fix the code.

## 3. Download and install the APK

1. Click the finished (green) run.
2. Under **Artifacts** at the bottom, click **CarVoicePlayer-debug-apk**
   to download a zip.
3. On your tablet: unzip it (any file manager app can do this, or a zip
   app from the Play Store), you'll get `app-debug.apk`.
4. Tap it. Android will ask to allow installing from this source the
   first time - allow it, then Install.
5. Open the app, tap **Start listening**, grant the mic/storage/notification
   permissions it asks for, and say "john play".

## What this app actually does now
- **Real GUI**: song list + now-playing panel side by side in landscape
  (rotates freely - "screenOrientation" is no longer locked to portrait,
  which was the bug preventing this before), stacked vertically in
  portrait.
- **Library screen**: every song on the device, searchable by typing,
  tap any song to play it. The currently-playing song is highlighted.
- **Settings screen** (tap the gear icon): change the wake word/aliases,
  **add specific music folders** via Android's real folder picker (for
  an SD card or a folder MediaStore doesn't pick up), rescan the
  library, switch Dark/Plain theme, **auto-open on boot**.
- **Auto-open when the car/tablet turns on** (off by default - turn it
  on in Settings): launches the app, resumes the last song from exactly
  where it left off, and starts playing automatically - no taps needed.
  Works even if the screen stays locked/off; audio starts regardless.
  Saved periodically during playback (every ~3s), not just on a clean
  exit, since a car's ignition turning off is an abrupt stop with no
  chance to save on the way out otherwise. Some tablets (especially
  budget/car-head-unit Android boxes) additionally require enabling
  "autostart" or "background activity" for this app in the device's own
  system settings - that's a manufacturer restriction this app can't
  bypass, so if auto-open doesn't work after enabling it here, check
  there next.
- **Volume is now stable**: your chosen volume (the slider on the main
  screen) is saved and is the ONLY baseline used everywhere - ducking
  for a voice command or a spoken confirmation always restores to
  exactly that level afterward, never to full volume and never stuck
  low. This runs through Android's real audio-focus system rather than
  a hand-rolled volume number, so it also plays correctly alongside
  other apps that request audio focus.
- Listens continuously in the background (foreground service, so it
  keeps working with the screen off) for wake words "john" and "sam"
  (each also works with "hey" in front - "hey john", "hey sam").
- Commands: "play", "pause", "next", "previous", "status", "play <song
  name>", "skip 30 seconds" / "skip 1 minute" (CURRENT SONG ONLY, a
  one-time jump - not saved anywhere, doesn't affect other songs), "skip
  all songs 30 seconds" (the GLOBAL setting - applies to every song,
  current and future; same as the Skip control in Settings), "trim forty
  thirty" (per-song front/end cut points, saved for that song - numbers
  are multiples of ten: zero/ten/twenty/thirty/forty/fifty/sixty), and a
  number 1-5 for rating.
- Mic input switches automatically if you connect a different
  microphone (Bluetooth, wired, USB) - no manual device picker, same as
  the Windows app not having one. Bluetooth mic routing specifically can
  vary by device/headset - if it doesn't route through Bluetooth
  automatically, that's the one part of this worth testing on your
  actual hardware, since it can't be fully verified without it.
- If both a global skip AND a song's own trim start are set, whichever
  is LARGER wins for where that song starts - matches the Windows app
  exactly, so a global skip never gets silently overridden by a smaller
  per-song trim point.
- Ducking (for a voice command or a spoken confirmation) requires the
  wake word to be heard consistently for a moment, not just a single
  stray-sounding blip - fixes volume dipping for no reason on a false
  alarm (e.g. something in a song's lyrics sounding vaguely like "john").
- Playlist wraps around at the end instead of stopping.
- Speaks a short confirmation out loud after each command.
- Scanning a large/multi-folder library shows live progress (files
  checked, songs found so far) in Settings instead of looking frozen.
- **Long-press any song** for Play / Song Info / Delete. A trash-icon
  button in the now-playing panel deletes whatever's currently loaded.
  Deleting actually removes the file from your device (not just from
  this app's list) - Android requires a one-tap OS confirmation for
  files this app didn't create itself, which is a platform requirement,
  not something this app is choosing to add.
- If Settings shows something under "Last error/crash": something was
  caught and handled without crashing the app, but it's worth telling
  me about if you see one - copy the text there and send it over.

## If you added folders BEFORE this update
Folders added via "Add Music Folder" before this version only had read
permission - deleting a song from one of those folders will fail. Go to
Settings, remove and re-add any folders you added previously so they
pick up write permission too. Folders added from now on don't need this.

## Two things worth knowing about rating/trim on this build
- **Rating and trim are now saved and persist** across restarts - by
  voice ("john 4", "john trim forty thirty") or by tapping the stars /
  dragging the trim sliders in the app. Whichever you use, they save to
  the same place and stay in sync.
- They're saved **inside the app**, not written into the song's own file
  tags the way the Windows app does. Doing a real file-tag write on
  Android needs a separate per-file user-consent flow
  (`MediaStore.createWriteRequest`) - a real but separate addition from
  this. Practically: the rating/trim will always show up again in this
  app, but won't show up if you plug the file into another music player.
- **"delete"/"undo"** are recognized but just say "not supported yet" -
  there's no trash-folder concept on Android the way the Windows app has.

## Want a different/more accurate speech model?
The build downloads `vosk-model-small-en-us-0.15` (40MB, good enough for
short commands) automatically - that's the `curl` line in
`.github/workflows/build-apk.yml`. To use a bigger model instead, open
that file right in GitHub's browser editor (pencil icon), swap the URL
for one from https://alphacephei.com/vosk/models, commit, and it'll
rebuild with that one instead. Bigger models mean a bigger APK and a
slower first build - the small one is the right starting point.
