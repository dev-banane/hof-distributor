# OMSI HOF Distributor

Collects every `.hof` file from your OMSI 2 vehicle folders (and anything under `maps`) and copies
them into every selected vehicle folder, so every bus has every HOF.

## Download

Grab the latest `OMSI-HOF-Distributor-...-windows.zip` from the [Releases](https://github.com/dev-banane/hof-distributor/releases) page,
unzip it anywhere and run `OMSI HOF Distributor.exe`. No Java installation needed.

The app finds your OMSI 2 folder through Steam and remembers the folder you last used
(`%APPDATA%\OMSI HOF Distributor\settings.properties`).

## How it works

- **Vehicles:** folders with a `.bus` file are ticked by default. Ticked folders are both sources and targets.
- **HOF files:** if the same file exists in different versions (e.g. `Grundorf.hof` in MB_O305 vs. MAN_SD200),
  pick the version to spread from the dropdown.
- **Changes:** a preview of exactly what gets written. Nothing happens until you press **Distribute**.
- By default only **missing** files are added and existing files are never touched. Tick "Replace files that differ"
  to also replace them; the old file is kept as `*.hof.hofdist.bak`.
- **Undo last run** removes what the last run added and restores backups.

## Development

    run.bat                       # builds (Maven + JDK 21+) and opens the GUI
    mvn javafx:run                # alternative
    java -jar target\hof-distributor.jar --dry-run "<OMSI 2 folder>"   # preview only, no changes

To publish a release, push a tag: `git tag v1.0.0` then `git push origin v1.0.0`.
The [Release workflow](.github/workflows/release.yml) builds the Windows app and attaches it to a new GitHub release.
