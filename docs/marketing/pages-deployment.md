# Website deployment (GitHub Pages)

| Trigger | Result |
| --- | --- |
| push to `dev`, any other branch, PR | **nothing** (CI runs tests + build only) |
| push to `Release` touching `site/`, `docs/media/`, `CHANGELOG*.md` or the workflow | deploys that commit |
| a GitHub release is published | deploys the `Release` branch (the site reads the new release from the GitHub API anyway) |
| manual run ("Run workflow", input `ref`) | deploys `ref` (default `Release`) |

Flow: work on `dev` → PR/merge into `Release` → site redeploys. `release.yml` (tag `vX.Y.Z`) makes the GitHub release and the Discord post and does not deploy the site itself.

Preview: there is no separate preview environment. Preview locally with `scripts/preview-site.sh` (assembles `site/` + media into a temp folder and serves it on http://localhost:8080). A manual deploy of a non-`Release` ref **replaces production** until the next Release deploy.

Owner check: Settings → Pages → Source must be "GitHub Actions"; Settings → Environments → github-pages → deployment branches should allow `Release` (and tags if you want `release` events to work from tags; this workflow checks out `Release` explicitly).
