---
name: google-java-style
description: Use when writing, formatting, or reviewing Java code under Google Java Style Guide. Covers formatting, imports, naming, structure, Javadoc, and tooling (google-java-format, checkstyle, spotless, editorconfig).
---

# Google Java Style Guide — Enforcement

Based on https://google.github.io/styleguide/javaguide.html and google-java-format 1.24.0.

## Core Formatting

- **Indentation:** 2 spaces (not tabs, not 4). Continuation indent 4 spaces.
- **Column limit:** 100 characters.
- **Braces:** K&R style — opening brace on same line `if (cond) {`, closing brace on own line. Always use braces even for single statements.
- **Line wrapping:** Break before operator, indent wrapped line +4. Method declarations wrapped: each param on new line +4 indent if exceeds 100.
- **Whitespace:** No trailing, one space after comma/semicolon, one space around operators, no space before `(` or `[`.
- **Files:** UTF-8, LF (`\n`), no BOM, one top-level class per file.

## Naming

| Element | Convention | Example |
|---|---|---|
| Packages | lowercase, no underscores | `com.genportal.portal` |
| Classes/Interfaces/Enums | UpperCamelCase | `PortalProperties`, `ModuleRepository` |
| Methods | lowerCamelCase | `getSession`, `listWorkspaces` |
| Fields (non-static) | lowerCamelCase | `sessionService` |
| Static final | CONSTANT_CASE | `SESSION_COOKIE_NAME` |
| Parameters/locals | lowerCamelCase | `tenantSlug` |
| Type params | Single capital or UpperCamel | `T`, `RequestT` |

## Imports

- No wildcard imports (`import java.util.*` forbidden).
- Order: static imports last, non-static groups: `java.*`, `javax.*`, `org.*`, `com.*`. Blank line between groups. Alphabetical within group.
- Static imports: `import static org.junit.jupiter.api.Assertions.assertEquals;` after non-static.
- Example:
```java
import com.genportal.portal.config.PortalProperties;
import java.time.Instant;
import javax.crypto.Mac;
import org.springframework.stereotype.Service;
import static org.assertj.core.api.Assertions.assertThat;
```

## File Structure

```java
package com.genportal.portal.modules.portal;

import ...;

public class PortalService {
  private static final Logger log = LoggerFactory.getLogger(PortalService.class);
  private static final String HEALTH_QUERY = "SELECT 1";

  private final PortalRepository repository;

  public PortalService(PortalRepository repository) {
    this.repository = repository;
  }

  /**
   * Checks DB connectivity.
   *
   * @return true if SELECT 1 succeeds
   */
  public boolean dbStatus() { ... }
}
```

Order: package → imports → class Javadoc → class declaration → constants → fields → constructors → methods → inner classes.
- `private static final Logger log` always first field after constants.
- Constructors before methods; `@Autowired` on constructor (no field injection).
- Methods grouped by visibility: public before private.

## Javadoc vs Comments

- Public classes/methods: Javadoc required with `@param @return @throws`.
```java
/**
 * Decrypts an AES-256-GCM value.
 *
 * @param encrypted base64(iv+tag+ciphertext)
 * @return plaintext or null if tampered
 */
public String decryptApiKey(String encrypted) { ... }
```
- Package-private/private: plain `//` only if non-obvious. Never use `//` for Javadoc.
- Logging comments: `// HMAC payload = base64url(JSON) + "." + hmacSHA256(secret)` acceptable for parity logic.

## Code Style Rules

- Explicit types preferred over `var` unless obvious (`var map = new HashMap<String,String>()` ok).
- `final` on method params and locals discouraged unless needed.
- No abbreviations except `id`, `url`, `dto`.
- `Optional` not for fields/params — only for return values where absence expected.
- Prefer `String.format` or concatenation — not `StringBuilder` unless loop.
- Use `Logger` slf4j, not `System.out`. Prefix logs `log.info("[portal] listening on {}")`.
- Exceptions: custom `extends ResponseStatusException` or standard; never swallow.
- Annotations: one per line if multiple.

## Tooling (Maven)

```xml
<properties>
  <google-java-format.version>1.24.0</google-java-format.version>
  <checkstyle.version>10.20.0</checkstyle.version>
</properties>
<build><plugins>
  <plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId>
    <version>2.44.0</version>
    <configuration><java><googleJavaFormat><version>${google-java-format.version}</version><style>GOOGLE</style></googleJavaFormat></java></configuration>
    <executions><execution><goals><goal>check</goal></goals><phase>verify</phase></execution></executions>
  </plugin>
  <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-checkstyle-plugin</artifactId>
    <version>3.6.0</version>
    <configuration>
      <configLocation>checkstyle.xml</configLocation>
      <headerLocation>checkstyle-header.txt</headerLocation>
      <failsOnError>true</failsOnError>
    </configuration>
    <executions><execution><phase>validate</phase><goals><goal>check</goal></goals></execution></executions>
  </plugin>
</plugins></build>
```

`checkstyle.xml` based on `google_checks.xml` (https://github.com/checkstyle/checkstyle/blob/master/src/main/resources/google_checks.xml) with:
- `LineLength max=100`, `FileTabCharacter`, `Indentation` 2 spaces.
- `ImportOrder`, `CustomImportOrder`, `IllegalImport` no wildcard.

`.editorconfig`:
```
root = true
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 2
[*.md]
trim_trailing_whitespace = false
```

Run: `mvn spotless:apply` (format), `mvn spotless:check` (verify), `mvn checkstyle:check`.

## Verification Checklist

- [ ] `mvn spotless:check` passes (google-java-format 1.24 GOOGLE style, 2 spaces, 100 col)
- [ ] `mvn checkstyle:check` 0 errors (google_checks.xml)
- [ ] `.editorconfig` present and committed
- [ ] No wildcard imports, ordered imports (checkstyle `ImportOrder`)
- [ ] No line >100 chars, correct brace style
- [ ] Javadoc on all public API, no block comments elsewhere
- [ ] Logger naming `log`, prefixes `[module]`
