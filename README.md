# NLX Music

NLX Music is a music app project. This repository contains the source code used to develop the application.

> **Honest project status:** Features, compatibility, performance, and release readiness should be verified by building and testing the current code. This README does not claim that NLX Music is the “#1” music app or make unsupported rankings, awards, user-count, or performance claims.

## Project goals

- Provide a music listening experience through the features implemented in this repository.
- Keep development transparent and document known limitations honestly.
- Improve the project through reproducible bug reports and constructive contributions.

## Getting started

### Requirements

- Git
- A compatible Java Development Kit (JDK)
- Android SDK command-line tools and the Android SDK components required by the Gradle project
- Windows PowerShell, macOS Terminal, or Linux shell

Exact tool versions may depend on the Gradle and Android Gradle Plugin versions configured in this repository.

### Build a debug APK

On Windows, open PowerShell in the project root and run:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux, run:

```bash
./gradlew assembleDebug
```

The debug APK is normally created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The build can fail if the Android SDK, required SDK platforms/build-tools, Java version, or SDK licenses are not configured correctly. A successful build does not replace testing on a real device.

## Downloading the app

When an APK is published, use the project's GitHub Releases page. If no release is published, there may not be a ready-to-install APK available yet.

## Transparency and AI assistance

AI tools such as ChatGPT or Claude may be used during development or documentation. Their output is not proof that a feature works. Code and claims should be checked, and AI tools must not be used to invent rankings, reviews, awards, download counts, or “Top 1” status. No ranking is claimed by this project unless it is supported by a clearly identified, verifiable source.

## Contributing

Contributions, bug reports, and documentation improvements are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting changes.

## Security

Please do not publish passwords, signing keys, API tokens, private credentials, or personal data in issues or pull requests. Read [SECURITY.md](SECURITY.md) for responsible reporting guidance.

## License

See the [LICENSE](LICENSE) file in this repository for the project's license and terms.

## Links

- **Portfolio:** https://notlae.netlify.app
- **GitHub repository:** https://github.com/notlae/Nlx_music
- **Instagram:** https://www.instagram.com/biren_shrestha17
