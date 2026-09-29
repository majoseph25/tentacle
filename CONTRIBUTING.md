# Contributing to Tentacle

Thanks for helping. A few things to know first.

## Before you start

- **Bugs and ideas:** open an issue using the templates.
- **Security problems:** report them privately (see [SECURITY.md](SECURITY.md)), never as a public issue.
- **Bigger changes:** open an issue to discuss them first, so the work fits the design in
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Working on the code

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for setup, building, testing and debugging. In short:

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintRelease
```

must pass. Android CI runs the same checks on every pull request.

Please follow the project conventions:
- **Headers:** a copyright/SPDX header on every new source file.
- **Security rules have tests.** Anything that validates IDs, builds URLs or decides where the token goes
  needs a unit test.
- **No personal data:** no server addresses, usernames, tokens or device names in code, tests or docs.
- **Media3 paging:** never return more items than the requested `pageSize` from a library callback.

## Licensing of contributions

Tentacle is licensed under the [Apache License 2.0](LICENSE). By submitting a contribution you agree it's
licensed under the same terms (section 5 of the License). You keep the copyright to your contribution,
and you may add your name to the copyright header of files you substantially write.

The Tentacle name and logo aren't covered by the licence (see [NOTICE](NOTICE)). Don't change or reuse
them in forks.
