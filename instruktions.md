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
- In the configuration activity, before the `Generate key` button, show the label `SSH server:` and one single-line SSH target text field in `user@host:port` format.
- The SSH target field should use the hint `user@host:port`.
- The username is required and must be provided before `@`.
- The port is optional and defaults to the standard SSH port, `22`.
- Remember the SSH target between launches and save it while it is edited in the configuration activity.
- In the configuration activity, show a single-line shared folder field.
- The shared folder field should default to `~/Artifacts/android/`.
- Remember the shared folder between launches and save it while it is edited in the configuration activity.
- In the configuration activity, show a single-line terminal start folder field.
- If the terminal start folder is blank, the SSH terminal should open in the server default home directory.
- If the terminal start folder has a value, the SSH terminal should change to that directory immediately after connecting.
- In the configuration activity, show an `Upload screenshots to shared folder` checkbox.
- When this checkbox is enabled, request image read permission if needed and automatically upload newly created screenshot files to the configured shared folder over SFTP.
- The app should generate its own SSH key instead of asking the user to paste a private key.
- In the configuration activity, add a `Generate key` button below the SSH target field.
- Under `Generate key`, show a text field containing the generated public key for copying.
- Next to the public key text field, show a `Copy` button that copies the public key to the clipboard.
- Store the generated private key internally for SSH connections.
- Show a `Load files` button on the main screen.
- Keep clear vertical spacing between the `Load files` button and the APK download buttons.
- Use a polished visual style with consistent colors, readable text contrast, and comfortable spacing.

Connection behavior:

- After pressing `Load files`, connect to the server using the provided credentials.
- Load all regular files located on the server at the configured remote shared folder path.
- Display one button per file.
- Button labels should be the filenames.
- The file buttons should appear one below another in a scrollable view.
- Show a red `Delete` button next to every file button.
- Pressing `Delete` should show a confirmation dialog before removing the file from the shared folder.
- Use Toast messages for SSH errors.

Download behavior:

- Pressing a file button downloads that file.
- Show a Toast when the download starts.
- Show a Toast when the download completes or fails.
- Show a notification when the download completes. Tapping the notification should open the downloaded file with an appropriate app.
- Download files to a location that is easy to find in the phone file manager, so the user can install them manually.

Development process:

1. Create the project folder and this `instruktions.md` file.
2. Build a minimal blank APK that does not need to display anything yet.
3. Run `git init`.
4. Create an appropriate Android `.gitignore`.
5. Add files not ignored by `.gitignore`.
6. Commit with message: `Initial commit - blank app`.
7. Add frontend fields and the `Load files` button only, then commit.
8. Add SSH/SCP backend that lists APK files and displays buttons, then commit.
9. Add APK downloading from the file buttons, then commit.

Terminal behavior:

- Before implementing terminal UI changes, think through the command-entry workflow first and keep the controls compact, predictable, and terminal-like.
- The app includes a separate SSH terminal screen opened from the main screen.
- The terminal uses an SSH `ChannelShell` connected to the generated private key and configured SSH target.
- The shell is opened with PTY enabled, `xterm-256color` as the PTY type, and `TERM=xterm-256color`.
- The app passes the terminal size to the SSH shell with `setPtySize(...)`.
- Remote shell output is rendered through a `TerminalScreenBuffer` based on the Termux terminal emulator, so common cursor movement, clear-screen, line erase, colors, and bold text sequences are interpreted locally.
- Terminal output is shown in a scrollable, selectable monospace view.
- The terminal keeps a large scrollback history so older output remains available after it leaves the visible screen.
- New terminal output should auto-scroll only while the user is already at the bottom; it must not force-scroll down while the user is reading older output.
- Commands are entered through a single-line command field and sent with a square icon button showing a send arrow, or with the keyboard send action.
- Do not use a text `Send` button in the terminal command bar.
- Place a second square icon button next to the send button with a copy icon; tapping it copies the current text from the command field at the bottom of the terminal.
- Sending an empty command sends only Enter.
- The terminal provides a compact, narrower `Cancel` button that sends Ctrl-C to the remote shell to leave or interrupt an interactive Codex CLI session.
- Next to the compact `Cancel` button, provide a square sideways-arrow button that sends Tab to the remote shell for command autocomplete and copies the completed line back into the command field.
- When syncing autocomplete/history text back into the command field, measure the prompt boundary from the terminal cursor column, not from trimmed line text; prompts commonly end with a space and that separator must not be copied into the command.
- At the end of the bottom command bar, provide a square upward-arrow button that sends Up to the remote shell and copies the recalled previous command back into the command field.
- The terminal keeps the command input above the on-screen keyboard.
- While connected, the terminal starts a foreground service and keeps wake/Wi-Fi locks active for the session.
- The SSH session, shell channel, terminal screen buffer, keep-alive loop, and wake/Wi-Fi locks live outside `TerminalActivity`, so they survive activity recreation and navigation between app screens.
- Returning to the terminal screen reattaches the UI to the existing terminal session and renders the current buffered terminal output.
- The SSH session uses a keep-alive loop while the terminal remains connected.
- Changing activities must not disconnect the terminal session.
- Disconnect the terminal session when the user removes the app task.
