# BitChord fork — working rules

- **Commit fast, very often.** Every tiny tweak gets its own commit the moment it's done — do not batch multiple changes into one commit. This mirrors how large software teams work.
- This is Ava's fork (`Avaa2499/BitChord`) of `kushagrasinghx/BitChord`, remote `upstream`.
- `main` has no `desktopApp` module — it was removed upstream after v1.8. Desktop work branches off `desktop-build-30` (= `v1.8` tag + one installer-version bump), not `main`.
- Branch model: `dev` is where all ongoing work/commits land (currently based on `desktop-build-30`). `main` only moves on an actual release — merge/fast-forward `dev` into `main` and tag it, then build the APK (phone) and AppImage (PC) from `main`. Never commit release-only work directly to `main`.
- Packaging target: AppImage, managed/updated via Gearlever. Not rpm, not flatpak.
- Want in-app "Update" button to self-update (AppImage update-information metadata).
- Do not reach for a bundled full Chromium/JCEF for the PO-token/BotGuard problem — rejected as too heavy. Prefer GraalJS/Node + bgutils-js-style polyfill instead of JavaFX WebEngine.
- Branch merging of upstream's many branches into one: deferred, not current work.
