# Publishing to Maven Central

This project publishes **merv-client-api** to Maven Central using the **[Central Publisher Portal](https://central.sonatype.org/publish/publish-portal-maven/)** (recommended) with the `central-publishing-maven-plugin`. GroupId: **io.github.techelliiptica**.

## Prerequisites

1. **Central Portal account & namespace**  
   - Sign in at [central.sonatype.com](https://central.sonatype.com) and [register your namespace](https://central.sonatype.com/register/namespace) (e.g. `io.github.techelliiptica`).  
   - Complete any ownership verification (e.g. DNS TXT for a domain, or GitHub repo for `io.github.*`).

2. **Publishing user token**  
   - In [Central Portal → Account → Generate User Token](https://central.sonatype.com/account), create a token for publishing.  
   - Use the token **username** and **password** in `settings.xml` (see below).

3. **GPG key**  
   Artifacts must be signed. Create and publish a key:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

4. **Maven `settings.xml`**  
   Location: `~/.m2/settings.xml`.

## Maven `settings.xml` (Central Portal)

Use the **user token** from the Central Portal (not JIRA/OSSRH username/password). Server id must match the plugin: **`central`**.

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_TOKEN_USERNAME</username>
      <password>YOUR_TOKEN_PASSWORD</password>
    </server>
  </servers>
  <!-- Optional: for non-interactive GPG signing -->
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.passphrase>YOUR_GPG_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
  <!-- Uncomment to activate gpg profile: <activeProfiles><activeProfile>gpg</activeProfile></activeProfiles> -->
</settings>
```

Replace `YOUR_TOKEN_USERNAME` / `YOUR_TOKEN_PASSWORD` with the values from [Generate User Token](https://central.sonatype.com/account).

## Build and deploy

From the **merv-client** directory:

```bash
mvn clean deploy
```

The plugin will:

- Use your existing **sources** and **javadoc** JARs and **GPG signatures** (from maven-source-plugin, maven-javadoc-plugin, maven-gpg-plugin).
- **Generate checksums** (MD5, SHA1, SHA256, SHA512) for the bundle — see [checksums](https://central.sonatype.org/publish/publish-portal-maven/#checksums).
- Upload the bundle to the Central Portal, validate it, and (with `autoPublish=true` and `waitUntil=published`) publish to Maven Central.

For local install only (no publish):

```bash
mvn clean install -Dgpg.skip=true
```

## POM summary

- **groupId:** `io.github.techelliiptica`  
- **artifactId:** `merv-client-api`  
- **central-publishing-maven-plugin** (v0.10.0): uploads to [central.sonatype.com](https://central.sonatype.com), `publishingServerId=central`, `autoPublish=true`, `waitUntil=published`, `checksums=all`.  
- **maven-gpg-plugin**: signs artifacts.  
- **maven-javadoc-plugin** & **maven-source-plugin**: attach javadoc and sources (required by Central).  
- POM metadata: URL, SCM, license (Apache 2.0), developers, issueManagement.

## After publish

Consumers can use:

```xml
<dependency>
    <groupId>io.github.techelliiptica</groupId>
    <artifactId>merv-client-api</artifactId>
    <version>3.0.0</version>
</dependency>
```

- [Central Search](https://central.sonatype.com)  
- [Publish Portal Maven docs](https://central.sonatype.org/publish/publish-portal-maven/) (usage, credentials, checksums, plugin options)
