# Golden screenshots

Static copies of the desktop golden screenshots, so a rendered screen can be reviewed from the
repository page without checking out a branch or opening a `build/` directory.

| File | Copied from |
| --- | --- |
| `popup-light.png`, `popup-dark.png` | `desktop-user/src/fxTest/resources/golden/popup-combo-{light,dark}.png` |
| `popup-menu-light.png`, `popup-menu-dark.png` | `desktop-user/src/fxTest/resources/golden/popup-menu-{light,dark}.png` |

The goldens themselves are produced and asserted by `UserGoldenScreenshotFxTest` from the live
popup window, because a popup is not part of a stage capture. Regenerate them there with
`USER_UPDATE_GOLDENS=true ./gradlew :desktop-user:fxTest --tests '*UserGoldenScreenshotFxTest*'`
and copy the result here, so the pictures in this directory never describe styling the build no
longer produces. The popup contract they prove is described in `docs/ui-ux-tooling.md`.
