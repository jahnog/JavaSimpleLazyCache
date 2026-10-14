# Java Simple Lazy Cache

`SingleFactoryCaller` is a thread-safe single-flight caller. It runs at most one factory for a key while that call is in flight. Callers that ask for the same key before the call finishes share one future. The key is removed when the future completes, successfully or not, so the next call runs the factory again. Results are not retained.

`Main` is a small command-line sample. The tests do not cover it.

## Requirements

The build releases Java 11 bytecode. The Gradle wrapper is 9.8.1, so a JDK 11 or newer is enough and a separate Gradle install is not required.

## Build

```shell
./gradlew assemble
```

## Unit tests

```shell
./gradlew test
```

## Coverage

```shell
./gradlew jacocoTestReport
```

The HTML report is `build/reports/jacoco/test/html/index.html`. The Cobertura report is `build/reports/jacoco/test/cobertura-jacocoTestReport.xml`. Both are build output.

## Mutation testing

```shell
./gradlew pitest
```

The HTML report is `build/reports/pitest/index.html`. Verdicts are stored in `build/pitHistory.txt`. The first run analyzes every mutant. A later run skips a mutant when that class and its covering tests are unchanged. Delete `build/pitHistory.txt` to analyze every mutant again. A stored verdict can miss a behavior change that comes from a dependency other than a superclass or an outer class.

## License

MIT. See [LICENSE](LICENSE).
