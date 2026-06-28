# SshApkDownloader specification

Android app name: `SshApkDownloader`.

Implementation requirements:

- Write the app in Kotlin.
- Do not use Flutter.
- Store the project in `/home/debian/projects/SshApkDownloader`.
- Keep this specification file in the repository. Do not add it to `.gitignore`.

Initial UI:

- On launch, show two single-line text fields:
  - IP address.
  - SSH key.
- Remember both values between launches.
- The SSH key field must be obscured by default.
- The SSH key can be shown only after tapping an eye icon next to the field.
- Show a `Connect` button below the fields.

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
