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
  library, switch Dark/Plain theme.
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
  name>", "skip 30 seconds" / "skip 1 minute", "trim forty thirty" (jump
  40s in, stop 30s before the end - numbers are multiples of ten:
  zero/ten/twenty/thirty/forty/fifty/sixty), and a number 1-5 for rating.
- Playlist wraps around at the end instead of stopping.
- Speaks a short confirmation out loud after each command.

## Two things NOT wired up yet on this build (say so if you want them)
- **Rating isn't saved** - a 1-5 rating is acknowledged out loud but not
  written back to the song's file tags. Doing that on Android needs
  MediaStore's tag-write permission flow (a user consent dialog per
  write on newer Android versions) - a real but bigger addition.
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
