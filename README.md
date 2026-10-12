# vex-suite-common

What [Vexplore](../vexplore) and [Pix](../Pix) share: the sizes pictures are decoded at, the image viewer's
tree and its parts, the palette they are drawn in, and git's view of a folder. A library, not an application; both apps depend on it, so a
change here is a change to both.

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

view/               the viewer and its parts, on vexelray-gui.
  PictureViewer     the whole viewer tree: the picture, and a bar over it of Close, the name (Open, when the host
                    opens), Previous, the filmstrip, Next, Background and Keep shown, which fades when the
                    pointer rests, and when pinned takes room of its own. Told what to show (Showing); tells
                    the application what the user did (Host). Vexplore shows it in a second window, Pix as its whole
                    window.
  Lens              how the picture is looked at: zoomed about the pointer, moved, turned to any angle, and held
                    where it can be seen, springing back with its momentum. Pure, so it is tested without a window.
  Sharpen           at rest, the part on screen made again from the file's full-size pixels at exactly the size it
                    is drawn, and laid over the picture. While it moves, the picture is drawn as it was.
  Filmstrip         the neighbours' thumbnails, gliding one square along on a step.
  Tile, Icons       the viewer's controls: one square size, an icon alone, its word the accessible name.
  MaskIcon          the icon set's own art (vexelray-icons/utility/small), made once into a distance field and drawn
                    at any size and colour, with a glow or a shadow from the shader.
  Choice            one of a few, as a row of words: the Settings menu's filters.
  SuiteLook         the palette both apps wear, and the tokens the parts above draw with.
  SuiteType         the faces and sizes the parts above draw with.
```

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
