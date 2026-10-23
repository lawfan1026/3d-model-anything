# Pixel 8 object capture and replication

## Objective

Build an installable native Android capture app and a local Windows import, reconstruction, review and agent handoff workflow, based on Wes Bos's project. The first physical subject is a stationary LEGO car. Preserve shape, metric evidence, colours and fine surface detail. Printing and design changes follow capture verification.

## Task list

- [x] Read the Principles section of poteto-mode in full.
- [x] Phase A: Frame. Inspect source, local tools, repository state and fidelity limits.
- [x] Phase B: Design the workflow. Compare capture and reconstruction designs before implementation.
- [x] Phase C: Run the loop. Build and check each unit below.
- [x] Native Android SharedCamera JPEG capture, durable observations, measurements and ZIP export.
- [x] Local ZIP validation, immutable import, COLMAP 4.2.1 CUDA reconstruction and evidence packet.
- [x] Object review page with source images, texture-off geometry, model views and measured scale/check annotations.
- [x] Phase D: Keep the audit trail. Record decisions and real checks in `docs/pixel-decisions.tsv`.
- [x] Phase E: Verify and hand back. Build APK, run meaningful pipeline tests and browser flows, then report physical proof gaps.

## Throughput checkpoint

- Blocking first steps. Create an isolated clone and task branch. Inspect camera API and engine capabilities. Agree the capture format before code writing.
- Independent workstreams. Android owns `apps/object-capture-android/`. Laptop processing owns `tools/object-replica/`. Parent owns viewer, shared format, root scripts, integration and delivery.
- Shared mutable state. Workers have exclusive directories. Parent alone changes shared files and Git history. Imported captures and reconstruction runs each have their own directory.
- Smallest safe decomposition. Two implementation workers and parent integration. Independent review reads completed artifacts. No parallel edits to shared files.

## Decisions

Use SharedCamera still JPEGs for detail. Preserve ARCore camera evidence, optional depth and actual Camera2 metadata. CPU-resolution frames are an explicit fallback, not equivalent capture quality. Use local ZIP transfer first. No camera uploads, server accounts or LLM keys in the app.

Use one pinned COLMAP CUDA engine for laptop photogrammetry and surface texturing. The laptop has an RTX 4060. Keep geometry inferred from photos separate from optional coarse depth. Reuse Wes's posed-photo comparison approach, not room-specific reconstruction defaults.

The capture contract is `docs/pixel-object-capture-format.md`. Native capture status follows a state model. Imported bundles are validated at the file boundary. These choices apply Model the Domain, Boundary Discipline, Foundational Thinking and Separate Before Serializing Shared State. Prove It Works requires exercising actual artifacts and labelling missing phone and physical evidence.

## Verification predicate

Local completion requires an installable debug APK, loss-aware ZIP contract tests, actual engine command probes, a reconstruction exercise on a labelled synthetic photo dataset, and a working local import/review path. Synthetic evidence does not establish LEGO fidelity. Pixel 8 stream compatibility, saved photo quality, close focus, colour stability, scan repeatability and dimensional accuracy require an actual phone capture.

## Source and isolation

Base repository is `wesbos/3d-model-anything`, commit `31b2dc9`. Task branch is `codex/pixel8-object-scan`. Preserve upstream app and example assets. Reusable upstream code is MIT licensed. Its example textures have separate asset terms.
