import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { PLYLoader } from 'three/addons/loaders/PLYLoader.js';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';
import './style.css';

type Intrinsics = { fx: number; fy: number; cx: number; cy: number };
type RecoveredCamera = { photoId: string; width: number; height: number; intrinsics: Intrinsics; cameraToWorld: number[]; photoUrl?: string };
type Photo = { id: string; file: string; url: string; epoch: string; objectPass?: string; width: number; height: number; role: string; registeredCamera?: RecoveredCamera };
type Run = { id: string; status: string; stage?: string; error?: string; registeredCount?: number; photoCount?: number; model?: { meshUrl: string; textureUrl?: string }; cameras?: RecoveredCamera[]; warnings?: string[] };
type Measurement = { id: string; label: string; role: string; valueMm: number; runId?: string; predictedMm?: number; residualMm?: number };
type Project = { id: string; name: string; manifest: { notes?: string; device?: unknown }; photos: Photo[]; physicalMeasurements: Measurement[]; measurements: Measurement[]; latestRun?: Run; scale: { status: string; metersPerUnit: number | null; rmsResidualMm?: number }; warnings?: string[] };
type Summary = { id: string; name: string; photoCount: number };
type EngineStatus = { colmap: { available: boolean; version?: string; errors?: string[] } };
const app = document.querySelector<HTMLDivElement>('#app')!;
app.innerHTML = `
<header><div class="brand">Pixel <span>Replica</span><span class="badge">LOCAL WORKSPACE</span></div><div class="top-note">Capture the real object. Keep the evidence.</div></header>
<div class="layout"><aside><div class="eyebrow">Your captures</div><button id="import-side">+ Import scan</button><div class="project-list" id="projects"></div><div class="aside-bottom"><p>Your originals stay on this laptop. Reconstruction and review run locally.</p><span id="engine-status">Checking reconstruction engine…</span></div></aside>
<main><div class="intro"><div><div class="eyebrow" style="margin-bottom:12px">OBJECT CAPTURE / PIXEL 8</div><h1 id="title">From real object to replica.</h1><p id="subtitle">Bring in your phone capture, reconstruct its surface, then check the model against the original.</p></div></div>
<div class="step-row"><span class="step">01 · Capture on phone</span><span class="step active">02 · Import to laptop</span><span class="step" id="reconstruct-step">03 · Reconstruct</span><span class="step" id="check-step">04 · Compare & measure</span></div>
<div id="notice" class="notice" role="status" aria-live="polite"></div>
<section id="empty"><div class="dropzone" id="dropzone"><div><h2>Start with a scan bundle.</h2><p>Export a ZIP from the Pixel Replica Android app. Copy it to your laptop by USB, then select it here.</p></div><button class="primary" id="import-main">Choose scan ZIP</button></div><div class="empty-grid"><div class="card"><div class="number">01 / ORIGINALS</div><h3>Keep every useful view.</h3><p>Photos, camera evidence and physical measurements travel together. Original files stay unchanged.</p></div><div class="card"><div class="number">02 / GEOMETRY</div><h3>Reconstruct the surface.</h3><p>The laptop uses overlapping photographs to recover shape and colour. Missing views remain visible.</p></div><div class="card"><div class="number">03 / EVIDENCE</div><h3>Check before you trust.</h3><p>Inspect the actual mesh, compare photographs and test dimensions with independent measurements.</p></div></div></section>
<section id="workspace" hidden><div class="toolbar"><span class="status-line" id="capture-summary"></span><span class="spacer"></span><label for="epoch" class="muted">Object pass</label><select id="epoch"></select><label for="detail" class="muted">Detail</label><select id="detail"><option value="3200">High · up to 3200 px</option><option value="6000">Full · up to 6000 px</option><option value="1600">Quick check · 1600 px</option></select><button class="primary" id="reconstruct">Reconstruct object</button></div><div class="notice" id="run-status"></div>
<div class="review-grid"><div class="panel"><div class="panel-heading"><h2>Original photograph</h2><small id="photo-info"></small></div><div class="image-frame"><img id="source" alt="Selected original capture" hidden><div class="placeholder" id="photo-empty">Select a photograph below.</div><span class="frame-tag" id="photo-role" hidden></span></div><div class="filmstrip" id="photos"></div></div>
<div class="panel"><div class="panel-heading"><h2>Reconstructed surface</h2><small id="model-info">Waiting for reconstruction</small></div><div class="model-frame" id="viewport"><div class="placeholder" id="model-empty">Reconstruct the object to inspect its surface here. You can review the original photographs now.</div><span class="frame-tag" id="view-mode" hidden>Orbit view</span></div><div class="model-actions"><button id="orbit" disabled>Orbit</button><button id="match" disabled>Match photo view</button><button id="texture" disabled>Colour on</button><button id="wireframe" disabled>Wireframe</button><button id="export-glb" disabled>Save scaled GLB</button></div></div></div>
<div class="details-grid"><div class="card"><h2>Check the size</h2><p>Choose two points on the model that match a measured distance on the real object. Use a separate distance to check the result.</p><div id="scale-status" class="status-line">Scale has not been established.</div><form id="measurement-form" class="measurement-form"><label class="wide">Distance label<input id="measure-label" placeholder="For example, rear axle width" required maxlength="120"></label><label>Measured distance, mm<input id="measure-value" type="number" min="0.01" step="0.01" required></label><label>Use as<select id="measure-role"><option value="scale">Set scale</option><option value="check">Check size</option></select></label><label>Pick on model<button type="button" id="pick" disabled>Pick 2 points</button></label><div class="wide status-line" id="pick-status">No points selected.</div><button class="wide" id="save-measurement" disabled>Save measurement</button></form><ul id="measurements" class="measurement-log"></ul></div>
<div class="card"><h2>Review the evidence</h2><p>Build a local packet with source photographs, reconstruction diagnostics and a prompt for your coding agent. Nothing is uploaded automatically.</p><button id="evidence">Prepare agent packet</button><div id="evidence-links" class="evidence-links"></div><details><summary>Capture and reconstruction notes</summary><ul id="warnings" class="warning-list"></ul></details></div></div></section>
<footer>Shape, scale and colour are checked separately. A successful reconstruction does not establish physical accuracy or printer fidelity. Based on Wes Bos’s 3D Model Anything capture-and-compare workflow.</footer></main></div><input id="file" type="file" accept=".zip,application/zip" hidden>`;

function el<T extends HTMLElement = HTMLElement>(id: string): T { return document.getElementById(id) as T; }
let project: Project | null = null;
let selectedPhoto: Photo | undefined;
let engineReady = false;
let busy = false;
let exporting = false;
let loadingProject = false;
let modelUrl = '';
let mesh: THREE.Mesh | null = null;
let texture: THREE.Texture | null = null;
let camera: THREE.PerspectiveCamera;
let controls: OrbitControls;
let renderer: THREE.WebGLRenderer | null = null;
let photoView = false;
let colourOn = true;
let wireOn = false;
let picking = false;
let points: THREE.Vector3[] = [];
let modelGeneration = 0;
const scene = new THREE.Scene();
const markers = new THREE.Group();
scene.add(markers);
scene.add(new THREE.HemisphereLight(0xffffff, 0x404653, 2));
const key = new THREE.DirectionalLight(0xffffff, 3); key.position.set(3, 5, 4); scene.add(key);

function notice(message = '', error = false) { el('notice').textContent = message; el('notice').classList.toggle('error', error); }
async function api<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || `Request failed (${response.status})`);
  return payload as T;
}
async function attempt(action: () => Promise<void>) { try { await action(); } catch (error) { notice(error instanceof Error ? error.message : String(error), true); } }
function post(body: unknown): RequestInit { return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }; }
function projectPath(suffix = '') { if (!project) throw new Error('Select a capture first.'); return `/api/projects/${encodeURIComponent(project.id)}${suffix}`; }

async function refreshLibrary() {
  const captures = await api<Summary[]>('/api/projects');
  const list = el('projects'); list.replaceChildren();
  if (!captures.length) { const text = document.createElement('small'); text.textContent = 'No captures imported yet.'; list.append(text); }
  for (const capture of captures) {
    const button = document.createElement('button'); button.className = `project-button${capture.id === project?.id ? ' selected' : ''}`;
    const name = document.createElement('span'); name.textContent = capture.name;
    const count = document.createElement('small'); count.textContent = `${capture.photoCount} photographs`;
    button.append(name, count); button.onclick = () => { void attempt(() => loadProject(capture.id)); }; list.append(button);
  }
}

async function loadProject(id: string) {
  if (loadingProject) return;
  loadingProject = true;
  try {
    const next = await api<Project>(`/api/projects/${encodeURIComponent(id)}`);
    resetModel();
    project = next; selectedPhoto = next.photos[0];
    el('evidence-links').replaceChildren(); notice();
    el('empty').hidden = true; el('workspace').hidden = false; el('title').textContent = next.name;
    el('subtitle').textContent = 'Original capture, reconstructed surface and measurement evidence in one place.';
    // Android 0.2+ groups photos by object placement; older captures only have the tracking epoch.
    const epochs = [...new Set(next.photos.map(photo => photo.objectPass ?? photo.epoch))];
    const select = el<HTMLSelectElement>('epoch'); select.replaceChildren(...epochs.map(epoch => { const option = document.createElement('option'); option.value = epoch; option.textContent = epoch; return option; }));
    renderPhotos(); renderProject(); await loadModel(); await refreshLibrary();
  } finally { loadingProject = false; }
}

function renderPhotos() {
  const strip = el('photos'); strip.replaceChildren();
  for (const photo of project?.photos || []) {
    const button = document.createElement('button'); button.className = `thumb${selectedPhoto?.id === photo.id ? ' selected' : ''}`; button.title = `${photo.id} · ${photo.width} × ${photo.height} · ${photo.role}`; button.setAttribute('aria-label', `View photograph ${photo.id}`);
    const image = document.createElement('img'); image.src = photo.url; image.alt = ''; image.loading = 'lazy';
    const label = document.createElement('span'); label.textContent = photo.role === 'holdout' ? 'CHECK' : photo.id;
    button.append(image, label); button.onclick = () => { selectedPhoto = photo; renderPhotos(); if (photoView) matchPhoto(); }; strip.append(button);
  }
  const image = el<HTMLImageElement>('source'); image.hidden = !selectedPhoto;
  el('photo-empty').hidden = !!selectedPhoto;
  el('photo-role').hidden = !selectedPhoto;
  if (selectedPhoto) {
    image.src = photoView ? matchedCamera()?.photoUrl || selectedPhoto.url : selectedPhoto.url; image.alt = `Source photograph ${selectedPhoto.id}`;
    const match = photoView ? matchedCamera() : undefined;
    el('photo-info').textContent = match ? `${match.width} × ${match.height} · lens-corrected comparison` : `${selectedPhoto.width} × ${selectedPhoto.height} · original`;
    el('photo-role').textContent = selectedPhoto.role === 'holdout' ? 'Held out of reconstruction' : 'Reconstruction input';
  }
  el<HTMLButtonElement>('match').disabled = !mesh || !matchedCamera();
}

function renderProject() {
  if (!project) return;
  const run = project.latestRun;
  el('capture-summary').textContent = `${project.photos.length} photographs · ${project.photos.filter(p => p.role === 'holdout').length} held out`;
  const running = run && ['queued', 'running'].includes(run.status);
  el<HTMLButtonElement>('reconstruct').disabled = busy || !engineReady || !!running;
  el('reconstruct').textContent = running ? 'Reconstruction running…' : 'Reconstruct object';
  el('run-status').textContent = run ? run.status === 'failed' ? `Reconstruction stopped. ${run.error || 'Inspect the engine logs.'}` : run.status === 'succeeded' ? `Surface reconstructed. ${run.registeredCount ?? '?'} of ${run.photoCount ?? '?'} input views registered. Verify the geometry and size below.` : `Reconstructing · ${run.stage || run.status}. You can keep reviewing the source photographs.` : '';
  el('run-status').classList.toggle('error', run?.status === 'failed');
  el('reconstruct-step').classList.toggle('active', !!running);
  el('check-step').classList.toggle('active', run?.status === 'succeeded');
  const scaled = (project.scale?.status === 'measured' || project.scale?.status === 'phone-estimate') && project.scale.metersPerUnit != null;
  el('scale-status').textContent = project.scale?.status === 'measured' ? 'Scale set from a physical measurement. Add an independent check.'
    : scaled ? `Scale estimated from the phone's motion tracking (fit error ${project.scale.rmsResidualMm?.toFixed(1) ?? '?'} mm). Check it against a ruler measurement, or set scale with one.`
    : 'Unscaled geometry. Set scale using measured endpoints.';
  el<HTMLButtonElement>('export-glb').disabled = !mesh || !scaled || exporting;
  const list = el('measurements'); list.replaceChildren();
  for (const item of (project.measurements || []).filter(item => item.runId === run?.id)) {
    const row = document.createElement('li'); const name = document.createElement('span'); name.textContent = item.label;
    const value = document.createElement('span'); value.textContent = item.role === 'scale' ? `${item.valueMm} mm · scale` : item.residualMm == null ? `${item.valueMm} mm · no scale` : `${item.residualMm >= 0 ? '+' : ''}${item.residualMm.toFixed(2)} mm error`;
    row.append(name, value); list.append(row);
  }
  const warnings = el('warnings'); warnings.replaceChildren();
  for (const warning of [...new Set([...(project.warnings || []), ...(run?.warnings || [])])]) { const item = document.createElement('li'); item.textContent = warning; warnings.append(item); }
}

function ensureRenderer() {
  if (renderer) return;
  renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2)); renderer.setClearColor(0x111416); renderer.outputColorSpace = THREE.SRGBColorSpace;
  camera = new THREE.PerspectiveCamera(45, 1, .001, 1000);
  createControls();
  el('viewport').prepend(renderer.domElement);
  let down = new THREE.Vector2();
  renderer.domElement.addEventListener('pointerdown', event => { down.set(event.clientX, event.clientY); });
  renderer.domElement.addEventListener('pointerup', event => {
    if (!picking || !mesh || !renderer || down.distanceTo(new THREE.Vector2(event.clientX, event.clientY)) > 5) return;
    const rect = renderer.domElement.getBoundingClientRect(); const position = new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1);
    const ray = new THREE.Raycaster(); ray.setFromCamera(position, camera); const hit = ray.intersectObject(mesh)[0];
    if (!hit) return;
    if (points.length === 2) { points = []; clearMarkers(); }
    points.push(hit.point.clone()); const bounds = new THREE.Box3().setFromObject(mesh); const size = bounds.getSize(new THREE.Vector3()).length() * .007;
    const marker = new THREE.Mesh(new THREE.SphereGeometry(size, 12, 8), new THREE.MeshBasicMaterial({ color: 0xc4f18d, depthTest: false })); marker.position.copy(hit.point); marker.renderOrder = 5; markers.add(marker);
    if (points.length === 2) { picking = false; const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), new THREE.LineBasicMaterial({ color: 0xc4f18d, depthTest: false })); line.renderOrder = 4; markers.add(line); }
    el('pick-status').textContent = points.length === 2 ? 'Two endpoints selected. Enter the physical distance.' : 'First endpoint selected. Pick the other end.';
    el<HTMLButtonElement>('save-measurement').disabled = points.length !== 2;
  });
  renderer.setAnimationLoop(() => { if (controls.enabled) controls.update(); renderer!.render(scene, camera); });
  new ResizeObserver(resize).observe(el('viewport'));
}

function clearMarkers() { while (markers.children.length) { const child = markers.children[0] as THREE.Mesh; markers.remove(child); child.geometry.dispose(); (child.material as THREE.Material).dispose(); } }
function createControls() {
  controls?.dispose(); controls = new OrbitControls(camera, renderer!.domElement); controls.enableDamping = true;
  controls.addEventListener('start', () => { if (photoView && !picking) orbit(); });
}
function resetModel() {
  ++modelGeneration; modelUrl = ''; photoView = false; picking = false; points = []; clearMarkers();
  if (mesh) { scene.remove(mesh); mesh.geometry.dispose(); (mesh.material as THREE.Material).dispose(); mesh = null; }
  texture?.dispose(); texture = null;
  if (controls) controls.enabled = false;
  for (const id of ['orbit', 'match', 'texture', 'wireframe', 'pick', 'export-glb', 'save-measurement']) el<HTMLButtonElement>(id).disabled = true;
  el('pick-status').textContent = 'No points selected.';
  el('model-empty').hidden = false; el('view-mode').hidden = true;
  el('model-empty').textContent = 'Reconstruct the object to inspect its surface here. You can review the original photographs now.';
  el('model-info').textContent = 'Waiting for reconstruction';
}
function resize() {
  if (!renderer) return;
  const frame = el('viewport'); let width = frame.clientWidth, height = frame.clientHeight;
  const match = photoView ? matchedCamera() : undefined;
  if (match) { const aspect = match.width / match.height; width = Math.min(width, height * aspect); height = width / aspect; }
  renderer.setSize(width, height);
  if (match) applyPhotoCamera(match); else { camera.aspect = width / height; camera.updateProjectionMatrix(); }
}

async function loadModel() {
  const run = project?.latestRun; const url = run?.status === 'succeeded' ? run.model?.meshUrl : '';
  if (url === modelUrl) return;
  resetModel();
  const generation = modelGeneration; modelUrl = url || '';
  if (!url) { el('model-info').textContent = 'Waiting for reconstruction'; return; }
  el('model-empty').textContent = 'Loading reconstructed surface…';
  try {
    ensureRenderer();
    const geometry = await new PLYLoader().loadAsync(url);
    if (generation !== modelGeneration) { geometry.dispose(); return; }
    if (!geometry.getAttribute('position')?.count) throw new Error('The reconstructed model has no vertices.');
    geometry.computeVertexNormals();
    const loadedTexture = run?.model?.textureUrl ? await new THREE.TextureLoader().loadAsync(run.model.textureUrl) : null;
    if (generation !== modelGeneration) { geometry.dispose(); loadedTexture?.dispose(); return; }
    texture = loadedTexture;
    if (texture) { texture.colorSpace = THREE.SRGBColorSpace; texture.flipY = true; }
    mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({ color: 0xffffff, map: texture, vertexColors: !texture && !!geometry.getAttribute('color'), side: THREE.DoubleSide }));
    scene.add(mesh); el('model-empty').hidden = true; el('view-mode').hidden = false;
    el('model-info').textContent = `${Math.floor((geometry.index?.count ?? geometry.getAttribute('position').count) / 3).toLocaleString()} triangles`;
    for (const id of ['orbit', 'texture', 'wireframe', 'pick']) el<HTMLButtonElement>(id).disabled = false;
    el<HTMLButtonElement>('match').disabled = !matchedCamera(); orbit(); updateMaterial(); renderProject();
  } catch (error) { if (generation !== modelGeneration) return; modelUrl = ''; el('model-empty').textContent = error instanceof Error ? error.message : String(error); notice('The surface could not be loaded. Inspect the reconstruction output.', true); }
}

function orbit() {
  if (!mesh || !renderer) return;
  photoView = false; controls.enabled = true;
  const bounds = new THREE.Box3().setFromObject(mesh); const center = bounds.getCenter(new THREE.Vector3()); const diagonal = Math.max(bounds.getSize(new THREE.Vector3()).length(), .001);
  camera.near = diagonal / 10000; camera.far = diagonal * 1000; camera.fov = 45;
  const up = new THREE.Vector3();
  for (const evidence of project?.latestRun?.cameras || []) up.add(new THREE.Vector3().fromArray(evidence.cameraToWorld, 4));
  const nextUp = up.lengthSq() > .000001 ? up.normalize() : new THREE.Vector3(0, 1, 0);
  if (!camera.up.equals(nextUp)) { camera.up.copy(nextUp); createControls(); }
  const evidence = matchedCamera();
  const direction = evidence ? new THREE.Vector3().fromArray(evidence.cameraToWorld, 12).sub(center) : new THREE.Vector3(1, .65, 1);
  camera.position.copy(center).add(direction.normalize().multiplyScalar(diagonal * 1.35)); camera.lookAt(center);
  controls.target.copy(center); controls.update(); el('view-mode').textContent = 'Orbit · drag to rotate'; resize(); renderPhotos();
}
function matchedCamera() { return selectedPhoto?.registeredCamera || project?.latestRun?.cameras?.find(item => item.photoId === selectedPhoto?.id); }
function applyPhotoCamera(value: RecoveredCamera) {
  const { fx, fy, cx, cy } = value.intrinsics; const w = value.width, h = value.height, n = camera.near, f = camera.far;
  const transform = new THREE.Matrix4().fromArray(value.cameraToWorld); transform.decompose(camera.position, camera.quaternion, camera.scale); camera.scale.set(1, 1, 1); camera.updateMatrixWorld(true);
  camera.projectionMatrix.set(2 * fx / w, 0, 1 - 2 * cx / w, 0, 0, 2 * fy / h, 2 * cy / h - 1, 0, 0, 0, -(f + n) / (f - n), -2 * f * n / (f - n), 0, 0, -1, 0);
  camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
}
function matchPhoto() {
  const value = matchedCamera(); if (!value || !renderer) return;
  const damping = controls.enableDamping; controls.enableDamping = false; controls.update(); controls.enableDamping = damping;
  photoView = true; controls.enabled = false; applyPhotoCamera(value); resize(); el('view-mode').textContent = 'Matched to recovered photo camera'; renderPhotos();
}
function updateMaterial() {
  if (!mesh) return;
  (mesh.material as THREE.Material).dispose();
  mesh.material = colourOn ? new THREE.MeshBasicMaterial({ map: texture, color: 0xffffff, vertexColors: !texture && !!mesh.geometry.getAttribute('color'), side: THREE.DoubleSide, wireframe: wireOn }) : new THREE.MeshStandardMaterial({ color: 0xaeb7bd, roughness: .8, side: THREE.DoubleSide, wireframe: wireOn });
  el('texture').textContent = colourOn ? 'Colour on' : 'Geometry only';
}

async function importFile(file?: File) {
  if (!file || busy) return;
  busy = true; notice(`Importing ${file.name} and checking the capture files…`);
  try { const imported = await api<Project>('/api/import', { method: 'POST', headers: { 'Content-Type': 'application/zip' }, body: file }); await loadProject(imported.id); notice('Capture imported. The original files are preserved.'); }
  finally { busy = false; el<HTMLInputElement>('file').value = ''; renderProject(); }
}
for (const id of ['import-side', 'import-main']) el(id).onclick = () => el<HTMLInputElement>('file').click();
el<HTMLInputElement>('file').onchange = event => { void attempt(() => importFile((event.target as HTMLInputElement).files?.[0])); };
el('dropzone').ondragover = event => { event.preventDefault(); el('dropzone').classList.add('drag'); };
el('dropzone').ondragleave = () => el('dropzone').classList.remove('drag');
el('dropzone').ondrop = event => { event.preventDefault(); el('dropzone').classList.remove('drag'); void attempt(() => importFile(event.dataTransfer?.files[0])); };
el('reconstruct').onclick = () => { void attempt(async () => { busy = true; renderProject(); try { await api(projectPath('/reconstruct'), post({ epoch: el<HTMLSelectElement>('epoch').value, maxImageSize: Number(el<HTMLSelectElement>('detail').value) })); project = await api<Project>(projectPath()); renderProject(); } finally { busy = false; renderProject(); } }); };
el('orbit').onclick = orbit; el('match').onclick = matchPhoto;
el('texture').onclick = () => { colourOn = !colourOn; updateMaterial(); };
el('wireframe').onclick = () => { wireOn = !wireOn; updateMaterial(); };
el('pick').onclick = () => { points = []; clearMarkers(); picking = true; el('pick-status').textContent = 'Pick the first endpoint on the model.'; el<HTMLButtonElement>('save-measurement').disabled = true; };
el('measurement-form').onsubmit = event => { event.preventDefault(); void attempt(async () => {
  if (points.length !== 2 || !project) return;
  const valueMm = Number(el<HTMLInputElement>('measure-value').value);
  const role = el<HTMLSelectElement>('measure-role').value;
  if (role === 'check' && project.scale.metersPerUnit == null) throw new Error('Set scale with a measured distance before adding a size check.');
  project = await api<Project>(projectPath('/measurements'), post({ label: el<HTMLInputElement>('measure-label').value, valueMm, role, a: points[0].toArray(), b: points[1].toArray(), runId: project.latestRun?.id }));
  renderProject(); notice('Measurement saved. Original capture files remain unchanged.');
}); };
el('evidence').onclick = () => { void attempt(async () => {
  const packet = await api<{ reportUrl: string; promptUrl: string; contactSheetUrl: string; hashesUrl: string }>(projectPath('/evidence'));
  const links = el('evidence-links'); links.replaceChildren();
  for (const [label, url] of [['Agent prompt', packet.promptUrl], ['Report', packet.reportUrl], ['Photo contact sheet', packet.contactSheetUrl], ['File hashes', packet.hashesUrl]]) { const link = document.createElement('a'); link.textContent = label; link.href = url; link.target = '_blank'; link.rel = 'noopener'; links.append(link); }
  notice('Agent packet prepared locally. Open the prompt to use it in Codex.');
}); };
el('export-glb').onclick = () => { void attempt(async () => {
  if (!mesh || !project?.scale.metersPerUnit || exporting) return;
  const exportProjectId = project.id;
  exporting = true; renderProject(); el('export-glb').textContent = 'Preparing GLB…'; notice('Preparing the textured model. Dense surfaces can take time to export.');
  const copy = mesh.clone(); copy.scale.setScalar(project.scale.metersPerUnit);
  copy.material = new THREE.MeshBasicMaterial({ color: 0xffffff, map: texture, vertexColors: !texture && !!mesh.geometry.getAttribute('color'), side: THREE.DoubleSide });
  try {
  const result = await new GLTFExporter().parseAsync(copy, { binary: true });
  if (!(result instanceof ArrayBuffer)) throw new Error('GLB export did not return a binary model.');
  const url = URL.createObjectURL(new Blob([result], { type: 'model/gltf-binary' })); const link = document.createElement('a'); link.href = url; link.download = `${exportProjectId}-measured.glb`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 10000);
  notice('Model export prepared. The GLB stores measured scale in metres and preserves the photographic texture.');
  } finally { copy.material.dispose(); exporting = false; el('export-glb').textContent = 'Save scaled GLB'; renderProject(); }
}); };

void attempt(async () => {
  const status = await api<EngineStatus>('/api/status'); engineReady = status.colmap.available;
  el('engine-status').textContent = engineReady ? `Reconstruction engine ready · ${status.colmap.version?.match(/COLMAP\s+\S+/)?.[0] || 'COLMAP'}` : 'Reconstruction engine needs setup. Original photos can still be reviewed.';
  await refreshLibrary(); renderProject();
});
setInterval(() => { if (!project || loadingProject) return; void attempt(async () => { const id = project!.id; const next = await api<Project>(projectPath()); if (project?.id !== id) return; project = next; selectedPhoto = next.photos.find(item => item.id === selectedPhoto?.id) || next.photos[0]; renderProject(); el<HTMLButtonElement>('match').disabled = !mesh || !matchedCamera(); await loadModel(); }); }, 2500);
