# AGENTS.md -- kotori

<!-- [VIBECODER-AGENTS-VERSION: v2.5.0] -->


## Translation pipeline: regression gate

Before and after ANY change under `app/src/main/java/mihon/feature/translation/`, run
`python regression/run.py` (MuMu debug build). It must be ALL CLEAR before you start, and every
difference after your change must be reviewed by eye and then blessed. Each fixed bug MUST add its
exposing page to `regression/corpus/`. Details: `regression/README.md`.
