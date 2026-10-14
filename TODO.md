# vex-suite-common — TODO

## Updates: an end-to-end version upgrade with the next release exes

`dev.sibarum.suite.update` and `UpdateNotice` have been checked in a native image, but not all of it in the build that
ships. Against a fake install record, a fake `update.ps1`, and the real https manifest:

- the release exe (`-Pnative-release`, GUI subsystem, no automation socket) fetched the manifest and showed the notice;
- the debug exe (`-Pnative`, console subsystem, automation) did the rest: **Update and restart** closed it, the hidden
  helper ran `update.ps1 -Yes` after it exited, and it started again and read the outcome. No console window appeared for
  the helper.

Nobody has pressed **Update and restart** in a release exe, and nothing has gone through the real `update.ps1` and
`install.ps1`. When the next versions of Pix and Vexplore are published, start from the previous release installed by
`vexelray-installer`. Let each application offer the new version itself, press the button by hand, and confirm:

- it closes, the real updater installs the new version, and it opens again on what was open;
- it then says it was updated to the new version, and the install record names that version;
- no console window flashes when the helper starts from the GUI-subsystem exe (the debug exe cannot show this);
- the notice reports a failed update correctly too: run it once with the network off during the update.
