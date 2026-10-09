# Personal Mihon build

Changes compared to upstream Mihon:

- **Google Drive backups.** After each automatic backup, the file is uploaded to a `Mihon backups` folder in
  Google Drive and only the newest few are kept there (10 by default). Set it up in
  *Settings → Data and storage → Google Drive*, which also has a "Back up to Google Drive now" button.
  The app only gets the `drive.file` permission, so it can see the files it uploaded and nothing else in your Drive.
- **Optional: extensions and downloaded chapters.** Two more switches in the same section. After each automatic backup,
  a separate job uploads only what's missing on Drive:
  - `Mihon backups/Extensions/<package>_v<version>.apk`, with only the newest version of each kept. To restore one,
    download the APK on your phone and install it.
  - `Mihon backups/Downloads/<source>/<manga>/<chapter>.cbz`, matching the local downloads folder. Chapters saved as
    image folders are zipped into CBZ files on the way up. To restore, copy the folders back into Mihon's `downloads`
    folder, then use *Settings → Advanced → Reindex downloads* if they don't show up right away.
    Chapters you delete on the phone are **not** deleted from Drive. By default this only runs on Wi-Fi.
- **Package name is `app.mihon.custom`**, so this build installs next to the official app instead of clashing with it.
  To move your library over, create a backup in the official app and restore it in this one.
- **No telemetry and no in-app updater.** The updater would offer official releases, which can't install over this build.

## One-time setup

### 1. Signing key

Android only updates an app if every version is signed with the same key, and Google uses the key's fingerprint to
recognise the app. So the GitHub build needs your own key, stored as repository secrets.

```sh
keytool -genkeypair -v -keystore personal.keystore -alias mihon -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 personal.keystore > personal.keystore.b64   # macOS: base64 -i personal.keystore -o personal.keystore.b64
```

Keep `personal.keystore` and its password somewhere safe. If you lose it, you can't install updates over the app.

In the GitHub repo, open *Settings → Secrets and variables → Actions* and add:

| Secret               | Value                                   |
|----------------------|-----------------------------------------|
| `SIGNING_KEY`        | contents of `personal.keystore.b64`     |
| `KEY_STORE_PASSWORD` | the keystore password                   |
| `ALIAS`              | `mihon`                                 |
| `KEY_PASSWORD`       | the key password (same as above if you only set one) |

### 2. Build

Open *Actions → Build personal APK → Run workflow*; it also runs on every push to `main`. When it finishes:

- download the `mihon-custom-…` artifact; for most phones, `app-arm64-v8a-release.apk` is the one to install
- copy the **SHA1** fingerprint from the "Print signing certificate SHA-1" step; you need it in the next step

### 3. Google Cloud project (for Drive access)

1. Go to <https://console.cloud.google.com/>, create a project (any name).
2. *APIs & Services → Library*, search **Google Drive API**, click **Enable**.
3. *Google Auth Platform* (or *OAuth consent screen*): fill in an app name and your email, choose **External**.
   Under *Audience*, click **Publish app**. `drive.file` is a non-sensitive scope, so Google doesn't need to review it.
   If you leave the app in "Testing" instead, add yourself as a test user and expect to reconnect every 7 days.
4. *Clients → Create client → Android*:
   - Package name: `app.mihon.custom`
   - SHA-1 certificate fingerprint: the value from the build log

You don't need to copy a client ID into the app. Google Play services matches the app by package name and fingerprint.

### 4. In the app

*Settings → Data and storage*: set the automatic backup interval, then turn on
**Upload automatic backups to Google Drive** and pick your account. Then tap **Back up to Google Drive now** to check it works.

If an upload fails, you get a notification and the local backup is kept. When Drive is on, automatic backups wait
for an internet connection.
