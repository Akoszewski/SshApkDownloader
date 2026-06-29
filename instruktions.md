# SshApkDownloader specification

Android app name: `SshApkDownloader`.

Implementation requirements:

- Write the app in Kotlin.
- Do not use Flutter.
- Store the project in `/home/debian/projects/SshApkDownloader`.
- Keep this specification file in the repository. Do not add it to `.gitignore`.

Initial UI:

- On launch, show the main content lowered slightly from the top of the screen.
- Show a small `Configuration` button in the top-right corner.
- The `Configuration` button opens a separate configuration activity.
- On launch, show one single-line text field:
  - SSH target in `user@host:port` format.
- The SSH target field should use the hint `user@host:port`.
- The username is required and must be provided before `@`.
- The port is optional and defaults to the standard SSH port, `22`.
- Remember the SSH target between launches.
- The app should generate its own SSH key instead of asking the user to paste a private key.
- In the configuration activity, add a `Generate key` button.
- Under `Generate key`, show a text field containing the generated public key for copying.
- Next to the public key text field, show a `Copy` button that copies the public key to the clipboard.
- Store the generated private key internally for SSH connections.
- Show a `Connect` button below the SSH target field.
- Keep clear vertical spacing between the `Connect` button and the APK download buttons.
- Use a polished visual style with consistent colors, readable text contrast, and comfortable spacing.

Connection behavior:

- After pressing `Connect`, connect to the server using the provided credentials.
- Load APK files located on the server at `~/Artifacts/android/`.
- Display one button per APK file.
- Button labels should be the APK filenames.
- The APK buttons should appear one below another in a scrollable view.
- Use Toast messages for SSH errors.

Download behavior:

- Pressing an APK button downloads that APK file.
- Show a Toast when the download starts.
- Show a Toast when the download completes or fails.
- Show a notification when the download completes. Tapping the notification should open the downloaded APK for installation.
- Download files to a location that is easy to find in the phone file manager, so the user can install them manually.

Development process:

1. Create the project folder and this `instruktions.md` file.
2. Build a minimal blank APK that does not need to display anything yet.
3. Run `git init`.
4. Create an appropriate Android `.gitignore`.
5. Add files not ignored by `.gitignore`.
6. Commit with message: `Initial commit - blank app`.
7. Add frontend fields and the `Connect` button only, then commit.
8. Add SSH/SCP backend that lists APK files and displays buttons, then commit.
9. Add APK downloading from the file buttons, then commit.

Do rozważenia:
  1. Termux terminal-view/emulator
  2. Podpiąć SSH ChannelShell input/output do emulatora
  3. Ustawić PTY: xterm-256color
  4. Przekazywać rozmiar terminala przez setPtySize(...)
  5. Obsłużyć klawiaturę specjalną: Ctrl, Esc, Tab, strzałki
