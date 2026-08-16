# Changelog

## 0.0.5 - 2026-08-16

### Added

- Run one background synchronization shortly after the first main window opens.
- Add optional periodic synchronization intervals: off, 1 minute, 5 minutes, 10 minutes, and 1 hour.

### Changed

- Apply remotely pulled changes before pushing the merged local state.
- Create missing WebDAV directories and the synchronization file when needed.
