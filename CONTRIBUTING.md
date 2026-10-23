# Contributing to NLX Music

Thank you for considering a contribution to NLX Music. Helpful contributions include bug fixes, improvements, tests, accessibility work, and clearer documentation.

## Before you start

1. Read the repository's `README.md`, `LICENSE`, and `CODE_OF_CONDUCT.md`.
2. Search existing issues and pull requests to avoid duplicating work.
3. For large changes, open an issue first to discuss the approach.

## Reporting bugs

Include:

- A clear description of the problem.
- Steps to reproduce it.
- Expected behavior and actual behavior.
- Device model, Android version, and app version or commit, when relevant.
- Relevant logs or screenshots, after removing personal information and secrets.

Do not claim a bug is fixed unless you have tested the change.

## Making changes

1. Create a branch for your work.
2. Keep changes focused and explain why they are needed.
3. Follow the style and structure already used in the codebase.
4. Add or update tests when practical.
5. Do not commit generated build outputs, local configuration, signing keys, credentials, or private data.
6. Do not add unsupported marketing claims, fabricated rankings, fake reviews, or invented usage statistics.
7. Clearly identify AI-assisted changes when relevant to review, and verify generated code instead of assuming it is correct.

## Build and test

For Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

For macOS or Linux:

```bash
./gradlew assembleDebug
```

Run available tests when appropriate:

```powershell
.\gradlew.bat test
```

A build or test command may require additional SDK components and licenses. In your pull request, state which commands you ran and whether they passed. Do not report tests as passing if you did not run them.

## Pull requests

A useful pull request includes:

- A concise title and summary.
- The reason for the change.
- The key files or behavior affected.
- Tests/build commands run and their actual results.
- Screenshots or recordings for visible UI changes, where useful.
- Known limitations or follow-up work.

Keep pull requests focused and be open to constructive review. By submitting a contribution, you agree that it may be reviewed under the repository's license and contribution process.

## Communication

Use GitHub issues and pull requests for project-related communication. Please keep discussions respectful, relevant, and transparent.
