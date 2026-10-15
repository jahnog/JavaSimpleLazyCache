# Java Simple Lazy Cache

`SingleFactoryCaller` is a thread-safe single-flight caller. It runs at most one factory for a key while that call is in flight. Callers that ask for the same key before the call finishes share one future. The key is removed when the future completes, successfully or not, so the next call runs the factory again. Results are not retained.

## Advantages

A throw, including an `Error`, or a null return becomes a failed future, and `getOrAddAsync` still returns that future.

A null key, an empty key, or a key whose `trim` is empty is rejected, and a null factory is rejected.

`clear` drops every in-flight entry. Futures already handed to callers still complete.

`getPendingTasksCount` returns the number of keys with a call still in flight.

## Commands

### Install

No install command exists.

The build releases Java 11 bytecode. The Gradle wrapper is 9.8.1, so a JDK 11 or newer is enough and a separate Gradle install is not required.

### Uninstall

No uninstall command exists.

### Run

No run command exists.

### Test

Run this from the repository root:

```shell
./gradlew test
```

### Debug

No debug command exists.

### Mutation test

Run this from the repository root:

```shell
./gradlew pitest
```

### Reports

Run this from the repository root:

```shell
./gradlew jacocoTestReport
```

The HTML report is `build/reports/jacoco/test/html/index.html`. The Cobertura report is `build/reports/jacoco/test/cobertura-jacocoTestReport.xml`. Both are build output.

## Build

Run this from the repository root:

```shell
./gradlew assemble
```

## Limitations

`Main` is a small command-line sample. The tests do not cover it.

The mutation HTML report is `build/reports/pitest/index.html`. Verdicts are stored in `build/pitHistory.txt`. The first run analyzes every mutant. A later run skips a mutant when that class and its covering tests are unchanged. Delete `build/pitHistory.txt` to analyze every mutant again. A stored verdict can miss a behavior change that comes from a dependency other than a superclass or an outer class.

## License

MIT. See [LICENSE](LICENSE).
