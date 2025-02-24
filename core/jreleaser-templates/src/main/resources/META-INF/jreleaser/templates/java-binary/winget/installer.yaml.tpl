# {{jreleaserCreationStamp}}
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.4.0.schema.json

PackageIdentifier: {{wingetPackageIdentifier}}
PackageVersion: {{wingetPackageVersion}}
ReleaseDate: {{wingetReleaseDate}}
Installers:
  - Architecture: neutral
    InstallerUrl: {{distributionUrl}}
    InstallerSha256: {{distributionChecksumSha256}}
    InstallerType: zip
    NestedInstallerType: portable
    NestedInstallerFiles:
      - RelativeFilePath: {{distributionArtifactRootEntryName}}\bin\{{distributionExecutableWindows}}
        PortableCommandAlias: {{distributionExecutableName}}
ManifestType: installer
ManifestVersion: 1.4.0
