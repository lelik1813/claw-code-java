# Contributing

## Prerequisites

- Java 21
- Git
- A shell that can run the Maven wrapper (`mvnw` or `mvnw.cmd`)

Do not commit local environment files, generated build output, IDE metadata, or secrets. Use `.env.example` and `.env.production.example` as templates for local configuration.

## Development Workflow

1. Create a focused branch for the change.
2. Make the smallest code and test changes needed for the feature or fix.
3. Run unit tests before opening a pull request:

```powershell
.\mvnw.cmd test -B
```

On Unix-like shells:

```sh
./mvnw test -B
```

4. Build the runnable artifact when you need to smoke test the CLI or application package:

```powershell
.\mvnw.cmd package -DskipUnitTests=true -B
```

The build produces a `target/claw-code-java-*.jar` artifact.


## Local Launch

After packaging, start the server and interactive CLI workflow with the `launch` command. Use a local environment variable or `.env` file for the model token; never paste real credentials into documentation or commits.

```powershell
$env:ANTHROPIC_AUTH_TOKEN="<model-token>"
.\mvnw.cmd exec:java "-Dexec.mainClass=com.clawcode.agent.cli.AgentCliApplication" "-Dexec.args=launch --port 8083 --persistence in-memory"
```

The command starts the local service with in-memory persistence and opens the CLI workflow against it.
## CLI Smoke Check

After packaging, run the CLI main class from the built jar:

```powershell
$jar = Get-ChildItem target -Filter "claw-code-java-*.jar" | Select-Object -First 1
java -cp $jar.FullName com.clawcode.agent.cli.AgentCliApplication --help
```

You can also use the provided smoke scripts when the application is running and the required local configuration is set.

## Integration Tests

Integration tests use Testcontainers and require a working Docker daemon. Run the full verification locally only when Docker is available:

```powershell
.\mvnw.cmd verify -B
```

If Docker is unavailable locally, rely on CI to run the integration path after pushing the branch.

## Pull Request Checklist

- Unit tests pass locally.
- Public examples contain placeholders only.
- No local paths, credentials, generated artifacts, or IDE files are included.
- CI is green before merge.