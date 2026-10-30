# Privacy Policy

_Last updated: October 29, 2026 (added expiry reminders and automatic backups)_

Papercase is an open-source app for storing your documents on your own device. This policy explains what the app does with your information. In short: **Papercase does not collect, store, or share any of your data. Everything stays on your devices.**

## What Papercase stores

Documents, photos, notes and your optional master passphrase are stored **only on your device**, encrypted with a key held in the Android Keystore. They are never sent to the developer or to any server.

## What Papercase does not do

- No accounts or sign-in.
- No analytics, advertising, tracking or crash reporting.
- No servers. The developer cannot see or recover your documents.

## Permissions

| Permission | Why it's used |
|---|---|
| Camera | To photograph documents and scan transfer QR codes. Photos are saved only inside the encrypted locker. |
| Biometric | To unlock the locker and your passphrase with your fingerprint or device PIN. Android handles the biometric check; the app never sees your fingerprint. |
| Internet, network and Wi-Fi state | Only for phone-to-phone transfers that you start, directly between your own devices on the same local network. Nothing is sent to the internet. |
| Notifications and foreground service | To show the clipboard auto-clear countdown, to tell you when the locker has locked, and for expiry reminders you turn on. On the lock screen, a reminder only says that a document expires soon. |

## Transfers and backups

- **Update check (Google Play version only):** when the app opens, it asks the Google Play Store app on your device whether a newer version is available, so it can suggest updating. The Play Store handles that check (it sends your device and app version details to Google, as it does for its own automatic updates) under Google's terms. The F-Droid and GitHub versions don't do this.
- **Phone-to-phone transfer** sends your encrypted locker directly to another device on your local network. It happens only when you start it and approve the receiving device.
- **Backup files** are created when you export one, or automatically if you turn on automatic backups, and saved only to the folder you choose. They are encrypted, and can only be opened with the export token or the backup password you set.
- **Expiry reminders** keep a small encrypted list of document titles and expiry dates on your device, so reminders can be sent without unlocking the locker. It never leaves your device.

What you do with a backup file afterwards (for example uploading it to a cloud drive) is up to you, and is covered by that service's own privacy policy.

## Clipboard

When you copy a document number or your passphrase, it is placed on the Android clipboard. If auto-clear is on, Papercase clears it after the timeout you choose. Some keyboards, such as Samsung Keyboard, keep their own clipboard history that other apps cannot clear.

## Children

Papercase is not directed at children and does not knowingly collect any information from anyone.

## Changes

Any changes to this policy will be published in this file in the [Papercase repository](https://github.com/mattintech/papercase), with the date updated above.

## Contact

Questions? Open an issue at https://github.com/mattintech/papercase/issues.
