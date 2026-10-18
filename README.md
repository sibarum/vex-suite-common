# vex-suite-common

What the suite's applications share: for [Vexplore](../vexplore) and [Pix](../Pix), the sizes pictures are decoded
at, the image viewer's tree and its parts, the palette they are drawn in, and git's view of a folder; for
[MainFrame](../mainframe) and [Vex](../text-editor-vexel-demo), a terminal. A library, not an application; each app
depends on it, so a change here is a change to all of them.

MainFrame and Vex use only `tty/` and exclude `vexelray-codec-image` from it, so neither carries the picture decoder.

```
mvn install        # build, test, and put it in the local Maven repository, before either app builds
```

Needs the VexelRay stack and [`vexelray-codec`](../vexelray-codec) (and `imagelib-wrapper` behind it) in the local
repository first, as the apps do.

Pictures themselves are `vexelray-codec-image`'s: decoding (`Pictures`, `Decoded`, `Picture`, `Stamp`), the cache
(`Lru`), the GPU (`Textures`) and one picture on a node (`ImageView`). Both apps name its `ImageStarter`, which builds
the `Textures` and binds them to the window. What is here is what is particular to the suite.

## What is here

```
pictures/
  Sizes             the two sizes the suite decodes at: a thumbnail's, and the viewer's from the same decode.

git/                the git executable, run as a process. No GUI. Blocking: callers run these on Gui.offload().
  Git               runs git with pipes, never taking a lock or prompting; a run is a Result (command, exit,
                    output) kept whole for whatever shows the user what git did. No git installed is not an error:
                    status() is empty, as it is outside a repository.
  Status            a working tree's state: the branch, every path that is not clean, and the two questions a file
                    list asks of it, at(file) and inside(folder). Case-blind where the platform is.
  Porcelain         reads `git status --porcelain=v2 -z --branch`. Pure, so it is tested without git.

update/             updating the installed suite from inside one of its applications, through vexelray-installer.
                    No GUI. Blocking.
  Install           what the installer's record (%LOCALAPPDATA%\vexelray-installer\installs\<id>.json) says: the
                    version, the publisher, the install folder, the published manifest's URL, the executable.
                    running() is whether this process is that executable; a build from source is not, and is never
                    offered updates. processes() are the other processes running it.
  Release           the latest release in a published manifest.json: its version, notes and download size. Pure.
  Versions          which version is newer, exactly as the installer's update.ps1 decides it.
  Updates           suite() is every install of the same publisher; survey() fetches all their manifests at once.
                    handover(...) starts a hidden script that runs each closed application's update.ps1 -Yes (which
                    hash-checks everything it downloads) and starts it again if it was open, then waits for this
                    application to exit and does the same for it, on the old version if an update failed.
                    outcome() is how that went, application by application, read once. lock() keeps it to one
                    update at a time across the suite.
  SuiteUpdate       the closing before the hand-over: each other open application to be updated is asked to close,
                    one at a time, as its own close button would ask, so it can ask about unsaved changes. Nothing
                    is ever forced. While one stays open the user can show it, skip it or cancel.
  Windows           the user32 calls behind that: WM_CLOSE posted to a process's main windows, and bringing one
                    to the front.

tty/                a terminal: a program on a pseudoconsole, its output as a screen, and the screen on a node.
  ConPty            a child process attached to a Windows pseudoconsole, through kernel32 by the foreign function
                    API. Output is VT sequences; input is keys as VT/UTF-8. Ends its output stream when the child
                    exits, which a pseudoconsole on its own never does.
  Terminal          a VT/xterm screen: a grid of cells, a 10 000-line history, and the parser that edits both from
                    a program's output. Pure, so it is tested without a window.
  Pump              a program's output read into a Terminal on a thread of its own, from a pseudoconsole or a pipe.
  TerminalScreen    a Terminal drawn on a node as a cell grid, fitted to the node, with the history scrolled by the
                    wheel and a drag selecting. Says what a key or a paste sends; sending it is the owner's.
  CellArt           box drawing, block elements and braille, drawn as rectangles so a TUI's lines meet.

view/               the viewer and its parts, on vexelray-gui.
  PictureViewer     the whole viewer tree: the picture, and a bar over it of Close, the name (Open, when the host
                    opens), Previous, the filmstrip, Next, Background and Keep shown, which fades when the
                    pointer rests, and when pinned takes room of its own. Told what to show (Showing); tells
                    the application what the user did (Host). Its commands are actions on a Keymap (view.next,
                    play.toggle, ...) with keys and touchpad gestures as defaults; two fingers always pan, and zoom
                    and turn only once they plainly mean to (Manipulation). Vexplore shows it in a second window, Pix
                    as its whole window.
  Lens              how the picture is looked at: zoomed about the pointer, moved, turned to any angle, and held
                    where it can be seen, springing back with its momentum; following two fingers, smoothed, about
                    their point, settling onto the nearest quarter turn if they turned it, and fitted if let go near
                    it. Pure, tested without a window.
  Manipulation      two fingers on the touchpad, from their raw contacts: always a pan, joined by a zoom once the
                    spread changes by half the travel and by a turn past 30° (at once, on a picture already askew),
                    each catching up as it joins.
  Sharpen           at rest, the part on screen made again from the file's full-size pixels at exactly the size it
                    is drawn, and laid over the picture. While it moves, the picture is drawn as it was.
  Filmstrip         the neighbours' thumbnails, gliding one square along on a step.
  Tile              the viewer's controls: one square size, an icon alone, its word the accessible name. The icons
                    are vexelray-gui-icons' Icon atlas, tinted and lit with the tile's state.
  Choice            one of a few, as a row of words: the Settings menu's filters.
  SuiteLook         the palette both apps wear, and the tokens the parts above draw with.
  SuiteType         the faces and sizes the parts above draw with.
  UpdateNotice      the word about updates, floating in the window's top right: "Pix 0.2.0 is available" (or
                    "3 updates are available") with Update and restart and Later; which application it is closing,
                    and Show, Skip and Cancel when one stays open; after an update, whether each one took. Silent
                    otherwise, offline too.
```

## Updates

Each application, when it is the installed copy, makes an `UpdateNotice` from `Updates.of(Apps.registryDir(), id)`,
appends it to its root and starts it: Pix in `Recipes.updates`, reopening the picture shown; Vexplore in
`Recipes.updates`, reopening the folder. The check is one GET of each suite member's manifest each start, in
parallel, so either application offers every update the suite has, its own or not. Updating closes the other open
applications that are being updated (each may ask about unsaved changes first; one that stays open can be skipped),
updates them and opens them again with nothing open, then does the same for this one if it is being updated; if it
is not, it stays open and reports. Applications that were not open, or have no update, are left alone. To see it without
publishing anything, point `INSTALL_REGISTRY_DIR` at a folder holding a record whose command is the `java.exe` that
`exec:exec` runs, whose `manifestUrl` is a manifest on a local server (`jwebserver`) naming a higher `latest`, and whose
`installDir` has an `update.ps1` of your own. Drive it by `--port`, not `--launch`: ottermate stops the launched process
tree when the application exits, and that tree includes the updater it handed over to.

What stays in each application is what is different about it: Vexplore's viewer is a second window that steps by
moving the explorer's selection, and its dock's preview ladder has text and bytes below the picture; Pix's is the
main window over a document of its own (a folder, its images, the one shown).

## Landmarks

`PictureViewer` names its parts for automation scripts with a prefix the application chooses: `name`, `info`,
`count`, `close`, `open`, `previous`, `next`, `background`, `keep`, `backdrop.dark` and the other backdrops. Pix uses no
prefix; Vexplore uses `viewer.`, and its viewer is reached with `ottermate --window viewer`.

## Application icons

The suite's icons are drawn on the design canvas; each application keeps its SVGs in `src/main/rc/` and links an
`.ico` into its executable. `tools/Ico.java` renders one SVG at every size Windows asks for (16 to 256 px, each from
the vector) and packs them, as PNGs, into an `.ico`. It is not part of the build; run it when an icon changes:

```
java --enable-native-access=ALL-UNNAMED \
     -cp ~/.m2/repository/sibarum/imagelib/imagelib-wrapper/0.1.0-SNAPSHOT/imagelib-wrapper-0.1.0-SNAPSHOT.jar \
     tools/Ico.java ../Pix/src/main/rc/pix.svg ../Pix/src/main/rc/pix.ico
```
