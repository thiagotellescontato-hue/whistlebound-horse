# Supported Versions

Each directory is an independent Fabric project configured and compiled for the
Minecraft version in its name.

| Minecraft | Java | Mappings |
| --- | --- | --- |
| 1.21.1 - 1.21.11 | 21 | Yarn |
| 26.1 - 26.2 | 25 | Official/unobfuscated |

Build one version from its directory:

```powershell
.\gradlew.bat build
```

The 26.x variants use the new unobfuscated Fabric Loom and the redesigned GUI
API. The horse-selection menu keeps the same 3D horse preview behavior across
the supported 1.21.x and 26.x builds.
