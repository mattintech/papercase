# Contributing to Papercase

Thanks for wanting to help! Papercase handles people's most sensitive documents, so changes are reviewed carefully. A short conversation up front saves everyone time.

## 1. Open an issue first

Before you write code, [open an issue](https://github.com/mattintech/papercase/issues/new) describing what you'd like to change and why:

- **Bug:** what you did, what you expected, what happened, your device and Android version. Please don't include real document details or screenshots of your documents.
- **Feature or change:** the problem it solves and roughly how you'd approach it.

Wait for a reply before starting larger work. It may already be planned, or there may be a reason it's done the way it is. Small fixes (typos, obvious one-line bugs) can go straight to a pull request.

## 2. Fork and branch

1. Fork the repository on GitHub.
2. Clone your fork and create a branch from **`develop`**:
   ```bash
   git clone https://github.com/<you>/papercase.git
   cd papercase
   git checkout develop
   git checkout -b fix/short-description
   ```

`main` holds released versions only. All work goes through `develop`.

## 3. Make your change

- Keep pull requests focused: one fix or feature per PR.
- Match the style of the surrounding code (Kotlin, Android views with ViewBinding, Material 3).
- Keep user-facing text plain and clear. No emoji in the UI.
- Add or update unit tests where it makes sense (`app/src/test`).
- Make sure it builds and the tests pass:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug
  ```
- If you change anything to do with encryption, keys, transfers or backups, explain the reasoning in the PR. Don't add network services, analytics, or new dependencies without discussing it in the issue first.

## 4. Open a pull request to `develop`

- Target the **`develop`** branch (not `main`).
- Link the issue (`Fixes #123`).
- Describe what changed and how you tested it, including the device(s) you used.

By contributing, you agree that your contribution is licensed under the [GPL-3.0](LICENSE), the same license as the project.

## Reporting security issues

Please **don't** open a public issue for security vulnerabilities. Report them privately through [GitHub's private vulnerability reporting](https://github.com/mattintech/papercase/security/advisories/new) instead. Include steps to reproduce and the impact you see. You'll get a reply as soon as possible.
