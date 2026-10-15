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

## Updates: the whole suite at once, in the shipped exes

Updating the suite from one application (`SuiteUpdate`, the plan-file script) has been run from Pix built from source,
against a fake registry served by `jwebserver`: an open installed Calculator was asked to close, closed, was updated
by a fake `update.ps1` and opened again, and Pix, which had no update of its own, reported it. A windowless process
standing in for an application that will not close got **Skip** and **Cancel**, and both did what they say. `WM_CLOSE`
was seen to close the installed Calculator release exe. Still to confirm, with the next published versions:

- **An application asking about unsaved changes.** Open Vex with an unsaved file and update from Pix: Vex should ask,
  Pix should say Vex is still open after three seconds, **Show** should bring Vex to the front (Windows allows it only
  while Pix is in the foreground, which it is when the button was just pressed), and answering Vex's question should
  let the update go on. Cancelling Vex's question and then pressing **Skip** should update the rest.
- **This application's own close refused.** With Pix being updated too, make its own close ask and say no: the others
  should still be updated and opened again, and Pix updated whenever it does close, up to fifteen minutes later.
- **The first suite update comes from the old helper.** Pix and Vexplore 0.1.0 carry the single-application script, so
  updating out of 0.1.0 updates only that application. The result file it writes is a bare exit code, which
  `outcome()` still reads. The suite-wide update is offered from the version after.
- **Native image.** The `user32` downcalls are declared in this jar's `reachability-metadata.json` (and the shapes are
  `vexelray-os-windows`'s too); check that Show and the close request work in `-Pnative-release`.
