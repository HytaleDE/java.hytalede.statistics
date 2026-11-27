## Releasing on GitHub

1. Use Java 25 and update the plugin version in `pom.xml` and `src/main/resources/manifest.json`.
2. Verify both Maven build profiles.

   ```bash
   bash ./mvnw -B -Pwith-hytale clean verify
   bash ./mvnw -B -Pstandalone clean verify
   ```

3. Merge the reviewed changes into `main`, then tag and push the merged commit. Replace the version in this example with the version in `pom.xml`.

   ```bash
   git checkout main
   git pull --ff-only
   git tag v1.0.2
   git push origin v1.0.2
   ```

The GitHub Actions release workflow verifies that the tag matches the Maven version. It builds the Hytale plugin against the official server Maven artifact and publishes the plugin JAR, its sources, and the standalone JAR on GitHub Releases.
