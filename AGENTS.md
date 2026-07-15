# Repository Guidelines

## Project Structure & Module Organization

Termora is a Kotlin/JVM desktop application. Core code lives in `src/main/kotlin/app/termora`, while application resources are under `src/main/resources`. Tests mirror the production package structure in `src/test/kotlin`; test fixtures belong in `src/test/resources`. Optional integrations are separate Gradle subprojects under `plugins/<name>` (for example, `plugins/s3` and `plugins/vnc`), each with its own `src/main` and, where needed, `src/test`. Documentation and README images live in `docs/`. Shared dependency versions are defined in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper; on Windows, replace `./gradlew` with `.\gradlew.bat`.

- `./gradlew run` launches Termora locally with the configured debug JVM options.
- `./gradlew test` runs the complete unit-test suite on JUnit Platform.
- `./gradlew :plugins:s3:test` tests one plugin module.
- `./gradlew classes` compiles main sources and processes resources.
- `./gradlew check-license` validates dependency licensing as CI does.
- `./gradlew jar copy-dependencies jlink` prepares application artifacts and a runtime image.

Use JetBrains Runtime where practical. Gradle configures a Java 25 toolchain, so ensure the required JDK can be resolved.

## Coding Style & Naming Conventions

Follow the official Kotlin style (`kotlin.code.style=official`) with four-space indentation and IDE formatting. Use `UpperCamelCase` for classes, `lowerCamelCase` for functions and properties, and lowercase dot-separated packages under `app.termora`. Keep plugin code in `app.termora.plugins.<plugin>`. Prefer focused files and existing Swing/coroutine patterns; do not introduce a new formatting tool without project-wide agreement.

## Testing Guidelines

Tests use Kotlin Test and JUnit 5. Name test classes `*Test.kt` and use descriptive test methods, including backtick names such as `` `parsePublicKey rejects garbage` ``. Add regression tests near the affected package. Tests requiring external services should be isolated and document their setup. No coverage threshold is enforced; prioritize meaningful behavior and edge cases.

## Commit & Pull Request Guidelines

Recent history follows Conventional Commit-style subjects: `feat:`, `fix:`, and `chore:`, optionally scoped, as in `feat(master-password): protect database encryption root`. Keep subjects imperative and concise. Pull requests should explain the problem and solution, list verification commands, link related issues, and include screenshots for visible UI changes. Call out platform-specific behavior and configuration or migration impacts.
