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

## First Pixel hardware run, 2026-10-23

- [x] Install and launch on the owner's Pixel 8 running Android 17.
- [x] Transfer and validate the actual native ZIP: 21 original 4080 × 3072 JPEGs, 19 reconstruction observations and two holdouts, one camera epoch, timestamp-matched still metadata.
- [x] Inspect the source photographs. Some frames are blurred. The owner confirms moving/turning the car, so retain this as a compatibility test instead of reconstructing it as one fixed scene.
- [x] Version 0.1.1 waits for settled repeating focus, preserves its AF mode in still capture and records both results. The three-photo Pixel check still reports final scanning metadata, so this is insufficient by itself.
- [x] Build and install version 0.1.2 with explicit START/confirmed-lock/JPEG/CANCEL handling; 23 unit tests pass. Independent source review found no P1/P2 findings.
- [x] Check version 0.1.2 on the Pixel: three confirmed START locks, unchanged reported focus distance, completed CANCELs and readable stud lettering. Preserve the final AF scanning discrepancy and glare limitations; this is not full fidelity proof.
- [ ] Capture a complete stationary-car set with independent physical dimensions and inspect reconstruction fidelity.

All 21 first-run still results report passive AF scanning. The old metadata cannot prove a mode-switch cause. The subsequent three-shot 0.1.1 check used matching repeating/still modes and still reported scanning in final results. Version 0.1.2 explicitly locks focus without replacing ARCore's repeating request. The phone test must check command evidence, final still metadata and full-resolution fine detail before a full recapture.

The 0.1.2 check confirms the lock command and cleanup; all three final JPEGs still report scanning with stationary lens and identical lock/JPEG focus distance. Visual checks show usable stud lettering, with glare obscuring the white plate and badge. Retain the current capture path and judge actual image detail instead of iterating solely on the AF flag. The owner needed the phone elsewhere before the full scan. Resume with the installed 0.1.2 app, a fresh stationary-car capture on a matte surface, even light, fixed still white balance requested after warm-up, overlapping views and independent physical measurements. No phone connection is needed while physical work is paused.
