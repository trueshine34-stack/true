# true

Static site published at **https://trueshine34-stack.github.io/true/**

## Contents

| Path | What it is | Published at |
|------|------------|--------------|
| `matraix-persona-8b/` | [MatrAIx Persona 8B](https://github.com/MatrAIx-ai/MatrAIx-Persona-8B) vendored at commit `1567aca` (MIT) | — |
| `site/` | Landing page + MkDocs overlay used to build the Pages site | `/` |
| `matraix-persona-8b/docs/` | MatrAIx Handbook sources, built with MkDocs Material | `/docs/` |
| `dressrentdubai/` | Dress rental studio landing page | `/dressrentdubai/` |

## How the site is built

`.github/workflows/pages.yml` runs on every push to `main` that touches the
site sources. It builds the MatrAIx Handbook with MkDocs Material, assembles
`_site/` (landing page at the root, handbook under `/docs/`, the existing
`dressrentdubai/` site kept at its current path), and deploys it with
`actions/deploy-pages`.

This requires **Settings → Pages → Source = "GitHub Actions"**. While the source
is still set to a branch, the workflow builds but the deploy step will not
publish.

To rebuild without pushing, run the *Deploy GitHub Pages* workflow manually from
the Actions tab.

## Running MatrAIx

GitHub Pages serves static files only. The MatrAIx runtime needs Docker, a
Python 3.12 backend, and model API keys, so it runs on your own machine — see
[the Quickstart](https://trueshine34-stack.github.io/true/docs/quickstart/):

```bash
cd matraix-persona-8b
uv venv --python 3.12
uv pip install -e .
uv pip install -e packages/playground

# smoke test — no API key required, Docker must be running
uv run harbor run -c configs/jobs/example-job-recipe/harbor-smoke-local.yaml
```

## License

`matraix-persona-8b/` is MIT-licensed by the upstream MatrAIx project — see
[`matraix-persona-8b/LICENSE`](matraix-persona-8b/LICENSE).
