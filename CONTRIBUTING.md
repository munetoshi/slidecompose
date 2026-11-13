# Contributing

Thank you for your interest in SlideCompose. Issues and pull requests are welcome.

## Before you start

- For a bug, open an issue with what you did, what you expected, and what happened instead.
- For a new feature or a large change, open an issue first, so that we can agree on it before you spend time on the code.
- Report security vulnerabilities privately, as [SECURITY.md](SECURITY.md) says, not in public issues.

## Pull requests

- Keep each pull request to one change.
- Add or update tests ([Kotest](https://kotest.io)) for what you change.
- Make sure `./gradlew build` passes. If you change the dependencies, run `./gradlew updateNotices` and check the changes to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Update the README and `docs/` when you change what users see.

[Development](docs/development.md) describes the structure, how it works and the tests.

## License

SlideCompose is licensed under the [Apache License 2.0](LICENSE).
As section 5 of the license says, any contribution you intentionally submit for inclusion is licensed under the same license, without any additional terms or conditions.
