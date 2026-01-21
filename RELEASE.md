## Releasing (GitHub)

### Preconditions
- Use **Java (Temurin) 25** for builds.
- Make sure your working tree is clean and tests pass:

```bash
./mvnw -B test
```

### Note about the Hytale server API dependency
The default build works without any local Hytale server JARs and is what CI/Release uses.

If you want to build the actual Hytale plugin entrypoint classes, you need the Hytale server API JAR available locally.

### Release by git tag (recommended)
1) Bump version in `pom.xml` if needed.
2) Commit the version bump.
3) Tag and push:

```bash
git tag v1.0.0
git push origin v1.0.0
```

This triggers the GitHub Actions workflow `Release` which builds the shaded JAR and uploads it to the GitHub Release.

### Release by command using GitHub CLI
If you use GitHub CLI (`gh`):

```bash
git tag v1.0.0
git push origin v1.0.0
gh release create v1.0.0 --generate-notes
```

