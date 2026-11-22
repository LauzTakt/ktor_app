// The 3D train on its track (Three.js, loaded on demand) shown on the on-tracks screen.

import { $, onTheme, reducedMotion } from "../common.js";
import { THREE_URL, clamp, cssVar, restartBtn, setUrl } from "./ui.js";

export function trainGone(s) {
  setUrl({});
  s.innerHTML = `<div class="finish"><h1>This train has ended its run</h1><p class="lead">It is no longer in service.</p></div>
    <div class="actions"><button class="btn btn-primary btn-block" id="again">Start again</button></div>`;
  $("#again", s).onclick = () => restartBtn.click();
}

// World units per second for 1 km/h: scaled down so the motion stays readable, not dizzying (100 km/h ≈ 5 u/s).
const KMH = 0.18 / 3.6;
const ARRIVE_DIST = 20; // the station appears this far ahead when the train stops at it...
const ARRIVE_SEC = 5.5; //   ...and the train brakes to the platform over this time
const WHEEL_R = 0.22;
const smooth = (x) => { const k = clamp(x, 0, 1); return k * k * (3 - 2 * k); };
const mix = (a, b, k) => a + (b - a) * k;

/**
 * Three.js scene: always the same train on the same straight track (not the real geometry). The train stays put; the
 * sleepers, poles, trees and ground move under it at a pace that follows the real speed, so a stopped train stands
 * still. When the train stops at a station, the platform comes in from ahead and the train brakes in one continuous
 * motion until it stands at it (the camera turns slightly towards the platform); when it leaves, it pulls away gently.
 */
export async function trainScene(canvas) {
  let THREE;
  try { THREE = await import(THREE_URL); } catch { canvas.closest(".scene").classList.add("no3d"); return null; }
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 2, 0.1, 260);
  // Two camera poses: along the line, and turned a little towards the platform while standing at a station.
  const POSE_LINE = { pos: [9, 6.4, 11], look: [-0.5, 0.6, -2] };
  const POSE_STATION = { pos: [7.4, 5.3, 12.4], look: [-1.7, 1.5, -1.2] };

  // Materials by colour token, so a theme change recolours everything at once.
  const dark = () => cssVar("--white") === "#17191c";
  const tone = (t) => ({
    body: dark() ? "#e8e9eb" : cssVar("--white"),
    wall: dark() ? "#d2d6db" : "#ffffff",
    glass: "#2a2d33", ink: "#212121", lamp: "#ffffff", tail: cssVar("--red"), line: "#ffffff",
  })[t] ?? cssVar(`--${t}`);
  const mats = [];
  const mat = (t, opts = {}) => {
    const m = opts.basic ? new THREE.MeshBasicMaterial() : new THREE.MeshLambertMaterial({ flatShading: !!opts.flat });
    m.color.set(tone(t));
    mats.push([m, t]);
    return m;
  };
  const box = (parent, w, h, d, m, x, y, z) => {
    const o = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), m);
    o.position.set(x, y, z);
    parent.add(o);
    return o;
  };

  scene.add(new THREE.HemisphereLight(0xffffff, 0x888888, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(6, 10, 8);
  scene.add(sun);

  // ---- Track and ground
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(200, 260), mat("milk"));
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);
  box(scene, 3.2, 0.2, 260, mat("cloud"), 0, 0.1, 0); // ballast
  const railMat = mat("iron");
  for (const x of [-0.72, 0.72]) box(scene, 0.1, 0.14, 260, railMat, x, 0.33, 0);
  box(scene, 0.03, 0.03, 260, mat("granite"), 0, 4.0, 0); // contact wire

  // Sleepers and catenary masts: one group shifted along z and wrapped every mast spacing (seamless).
  const SPACING = 0.7, MAST = SPACING * 15;
  const moving = new THREE.Group();
  scene.add(moving);
  const sleeperMat = mat("granite"), poleMat = mat("cement");
  for (let i = 0; i < 150; i++) box(moving, 2.2, 0.08, 0.26, sleeperMat, 0, 0.24, -80 + i * SPACING);
  for (let i = 0; i < 10; i++) {
    const pole = box(moving, 0.14, 4.2, 0.14, poleMat, -2.2, 2.1, -80 + i * MAST);
    box(pole, 2.4, 0.08, 0.08, poleMat, 1.15, 1.9, 0); // cantilever arm
    box(pole, 0.06, 0.5, 0.06, poleMat, 2.2, 1.65, 0); // dropper to the wire
  }

  // Trees on both sides (simple flat-shaded crowns) and distant hills that move slower (parallax).
  const crownMats = [mat("silver", { flat: true }), mat("cement", { flat: true })], trunkMat = mat("granite");
  const trees = [];
  const TREE_LOOP = 96, TREE_FROM = -80;
  const crownGeo = new THREE.IcosahedronGeometry(1, 0);
  const addTree = (x, z, h) => {
    const g = new THREE.Group();
    box(g, 0.18, h * 0.45, 0.18, trunkMat, 0, h * 0.22, 0);
    const crown = new THREE.Mesh(crownGeo, crownMats[trees.length % 2]);
    crown.scale.set(h * 0.38, h * 0.48, h * 0.38);
    crown.position.y = h * 0.62;
    crown.rotation.y = z;
    g.add(crown);
    g.position.set(x, 0, z);
    scene.add(g);
    trees.push(g);
  };
  // Deterministic layout (no Math.random): spread out, never on the track or the station plot.
  for (let i = 0; i < 12; i++) addTree(6.2 + (i * 1.7) % 3.2, TREE_FROM + i * 8 + (i % 3) * 2.1, 1.7 + (i % 4) * 0.25);
  for (let i = 0; i < 14; i++) addTree(-11.5 - (i * 2.3) % 7, TREE_FROM + i * 6.9 + (i % 2) * 3, 2.2 + (i % 3) * 0.5);
  const hills = new THREE.Group();
  scene.add(hills);
  const hillMat = mat("cloud", { flat: true });
  const HILL_LOOP = 7 * 34;
  for (let i = 0; i < 7; i++) {
    const hill = new THREE.Mesh(new THREE.ConeGeometry(14 + (i % 3) * 5, 6 + (i % 2) * 4, 7), hillMat);
    hill.position.set(-50 - (i % 3) * 14, 2.5, -120 + i * 34);
    hill.rotation.y = i;
    hills.add(hill);
  }

  // ---- Train: two coaches with a cab at each end (push-pull), flat SBB colours, no logos. It runs towards -z.
  const train = new THREE.Group();
  const bodyMat = mat("body"), bandMat = mat("red"), winMat = mat("glass"), roofMat = mat("cement"), inkMat = mat("ink");
  const doorMat = mat("silver"), lampMat = mat("lamp", { basic: true }), tailMat = mat("tail", { basic: true });
  const wheels = [];
  const coach = (z, cab) => {
    const g = new THREE.Group();
    box(g, 1.9, 2.0, 7.2, bodyMat, 0, 1.8, 0);
    box(g, 1.92, 0.22, 7.22, bandMat, 0, 1.2, 0);
    box(g, 1.7, 0.18, 7.0, roofMat, 0, 2.89, 0);
    box(g, 1.0, 0.22, 1.6, roofMat, 0, 3.07, z < 0 ? -1.6 : 1.6); // roof equipment, towards the cab
    box(g, 1.6, 0.3, 3.0, inkMat, 0, 0.72, 0); // underframe equipment between the bogies
    for (const w of [-1.95, -0.65, 0.65, 1.95]) box(g, 1.94, 0.6, 0.95, winMat, 0, 2.1, w);
    for (const dz of [-3.0, 3.0]) {
      box(g, 1.935, 1.62, 0.78, doorMat, 0, 1.63, dz); // door leaves, both sides
      box(g, 1.945, 0.5, 0.32, winMat, 0, 2.05, dz); // door window
      box(g, 1.945, 1.5, 0.02, inkMat, 0, 1.63, dz); // gap between the two leaves
    }
    if (cab) {
      const s = Math.sign(cab), nz = s * 4.05;
      box(g, 1.9, 1.3, 0.9, bodyMat, 0, 1.45, nz);
      const wind = box(g, 1.6, 0.55, 0.92, winMat, 0, 2.3, s * 3.67); wind.rotation.x = s * 0.35;
      box(g, 1.92, 0.22, 0.92, bandMat, 0, 1.2, nz);
      for (const lx of [-0.58, 0.58]) box(g, 0.26, 0.14, 0.04, s < 0 ? lampMat : tailMat, lx, 1.5, s * 4.51);
      if (s < 0) box(g, 0.22, 0.14, 0.04, lampMat, 0, 2.0, s * 4.47); // top headlight
      box(g, 1.2, 0.12, 0.3, inkMat, 0, 0.62, s * 4.4); // coupler fairing
    }
    for (const bz of [-2.6, 2.6]) {
      box(g, 1.3, 0.2, 1.9, inkMat, 0, 0.68, bz); // bogie frame
      for (const az of [-0.55, 0.55]) {
        for (const wx of [-0.78, 0.78]) {
          const axle = new THREE.Group();
          axle.position.set(wx, 0.4 + WHEEL_R, bz + az);
          const wheel = new THREE.Mesh(new THREE.CylinderGeometry(WHEEL_R, WHEEL_R, 0.1, 14), inkMat);
          wheel.rotation.z = Math.PI / 2;
          axle.add(wheel);
          box(axle, 0.04, WHEEL_R * 1.5, 0.06, roofMat, wx > 0 ? 0.06 : -0.06, 0, 0); // spoke mark, shows the turn
          g.add(axle);
          wheels.push(axle);
        }
      }
    }
    g.position.z = z;
    train.add(g);
    return g;
  };
  const coaches = [coach(-3.8, -1), coach(3.8, 1)];
  box(train, 1.5, 1.9, 0.5, inkMat, 0, 1.75, 0); // gangway between the coaches
  // Pantograph on the leading coach, touching the contact wire
  const panto = new THREE.Group();
  panto.position.set(0, 2.98, -3.8 + 1.4);
  box(panto, 0.9, 0.1, 0.5, inkMat, 0, 0.05, 0);
  const lower = box(panto, 0.06, 0.06, 0.85, inkMat, 0, 0.36, 0.3); lower.rotation.x = -0.75; // folded arm: a "<"
  const upper = box(panto, 0.06, 0.06, 0.7, inkMat, 0, 0.825, 0.3); upper.rotation.x = 0.52;
  box(panto, 1.3, 0.05, 0.12, inkMat, 0, 1.0, 0);
  train.add(panto);
  scene.add(train);

  // ---- Station (platform, canopy, building, name sign). Its own materials, so it can fade in on its own.
  const station = new THREE.Group();
  const stationMats = [];
  const smat = (t, opts) => { const m = mat(t, opts); stationMats.push(m); return m; };
  const platMat = smat("cloud"), lineMat = smat("line"), wallMat = smat("wall"), canopyMat = smat("iron");
  const postMat = smat("granite"), trimMat = smat("cement"), sWinMat = smat("glass");
  // Platform along the far side of the track, with its white safety line and edge
  box(station, 3.4, 0.55, 26, platMat, -2.95, 0.28, 0);
  box(station, 0.14, 0.02, 26, lineMat, -1.55, 0.57, 0);
  box(station, 0.1, 0.56, 26, trimMat, -1.27, 0.28, 0);
  // Canopy on slim columns (shows above the train roof)
  box(station, 2.8, 0.14, 15, canopyMat, -2.7, 3.45, 0);
  box(station, 2.84, 0.24, 0.1, trimMat, -2.7, 3.32, 7.5);
  for (const cz of [-6, -2, 2, 6]) box(station, 0.12, 2.9, 0.12, postMat, -3.2, 2.0, cz);
  // Station building behind the platform: two floors, windows, door, cornice
  box(station, 4.2, 3.6, 9, wallMat, -7.0, 1.8, -3);
  box(station, 4.6, 0.25, 9.4, canopyMat, -7.0, 3.7, -3);
  box(station, 4.3, 0.12, 9.1, trimMat, -7.0, 1.95, -3);
  for (const wz of [-6.4, -4.8, -1.2, 0.4]) {
    box(station, 0.06, 0.8, 0.9, sWinMat, -4.88, 1.15, wz);
    box(station, 0.06, 0.8, 0.9, sWinMat, -4.88, 2.8, wz);
  }
  box(station, 0.06, 1.7, 1.2, postMat, -4.88, 0.85, -3);
  // Lamps, benches and a departure board on the platform
  for (const lz of [-9, 9]) { box(station, 0.08, 3.0, 0.08, postMat, -3.9, 1.95, lz); box(station, 0.5, 0.12, 0.25, trimMat, -3.7, 3.45, lz); }
  for (const bz of [-4.5, 4.5]) { box(station, 0.5, 0.08, 1.8, postMat, -3.8, 0.85, bz); box(station, 0.08, 0.45, 1.8, postMat, -4.05, 1.1, bz); }
  box(station, 0.1, 0.7, 1.4, sWinMat, -3.3, 2.75, -2);
  // Name sign hanging from the canopy edge, facing the camera
  const signCanvas = document.createElement("canvas"); signCanvas.width = 512; signCanvas.height = 96;
  const signTex = new THREE.CanvasTexture(signCanvas);
  signTex.colorSpace = THREE.SRGBColorSpace;
  const signMat = new THREE.MeshBasicMaterial({ map: signTex });
  stationMats.push(signMat);
  const sign = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 0.64), signMat);
  sign.position.set(-1.5, 4.05, 1.5); sign.rotation.y = 0.69; station.add(sign);
  box(station, 0.05, 0.5, 0.05, postMat, -1.5, 3.7, 0.4); box(station, 0.05, 0.5, 0.05, postMat, -1.5, 3.7, 2.6);
  const drawSign = (name) => {
    const g = signCanvas.getContext("2d");
    g.fillStyle = "#2d327d"; g.fillRect(0, 0, 512, 96);
    g.fillStyle = "#ffffff"; g.font = "700 64px 'SBB Web', Helvetica, Arial, sans-serif"; g.textBaseline = "middle";
    let txt = name || ""; while (g.measureText(txt).width > 472 && txt.length > 3) txt = txt.slice(0, -2) + "…";
    g.fillText(txt, 20, 52); signTex.needsUpdate = true;
  };
  let opacity = 1;
  const setOpacity = (o) => {
    if (o === opacity) return;
    const was = opacity < 1;
    opacity = o;
    for (const m of stationMats) {
      m.opacity = o;
      if (was !== o < 1) { m.transparent = o < 1; m.needsUpdate = true; }
    }
  };
  station.visible = false;
  scene.add(station);

  // ---- Motion. `vis` = visual speed (world units/s); `mode`: line | arriving | here | leaving.
  let mode = "line", stationName = null, first = true;
  let target = 0, vis = 0, arrival = null, leftAt = 0, cam = 0, raf = 0, t0 = performance.now();
  const startArrival = (now) => {
    const v0 = Math.min(vis, (3 * ARRIVE_DIST) / ARRIVE_SEC); // keeps the braking curve monotone (no backwards roll)
    arrival = { at: now, v0, p: 0 };
    station.position.z = -ARRIVE_DIST;
    station.visible = true;
    setOpacity(0);
    mode = "arriving";
  };
  // Distance covered after s ∈ [0, 1] of the arrival: starts at the current speed, ends standing (cubic Hermite).
  const arrivalPos = (s, v0) => ARRIVE_DIST * (3 * s * s - 2 * s * s * s) + v0 * ARRIVE_SEC * (s - 2 * s * s + s * s * s);

  const resize = () => {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };
  const ro = new ResizeObserver(resize);
  ro.observe(canvas);
  const look = new THREE.Vector3();
  const frame = (now) => {
    const dt = Math.min(0.05, (now - t0) / 1000);
    t0 = now;
    const still = reducedMotion();
    let delta = 0;
    if (mode === "arriving") {
      const s = Math.min(1, (now - arrival.at) / 1000 / ARRIVE_SEC);
      const p = arrivalPos(s, arrival.v0);
      delta = p - arrival.p;
      arrival.p = p;
      vis = dt > 0 ? delta / dt : vis;
      setOpacity(smooth(s / 0.3));
      if (s >= 1) { mode = "here"; vis = 0; setOpacity(1); }
    } else if (mode === "here") {
      vis = 0;
    } else {
      // Pull away from a station gently even before the feed reports speed; brake a little firmer than we accelerate.
      const goal = mode === "leaving" ? Math.max(target, Math.min(2.5, ((now - leftAt) / 1000) * 0.7)) : target;
      vis += (goal - vis) * Math.min(1, dt * (goal > vis ? 0.6 : 1.2));
      delta = vis * dt;
    }
    if (still) delta = 0;

    moving.position.z = (moving.position.z + delta) % MAST;
    for (const t of trees) { t.position.z += delta; if (t.position.z > TREE_FROM + TREE_LOOP) t.position.z -= TREE_LOOP; }
    for (const h of hills.children) { h.position.z += delta * 0.12; if (h.position.z > 100) h.position.z -= HILL_LOOP; }
    for (const w of wheels) w.rotation.x -= delta / WHEEL_R;
    if (station.visible) {
      station.position.z += delta;
      if (mode === "here") station.position.z = 0; // exactly at the platform, whatever the frame rate did
      if (mode === "leaving" && station.position.z > 45) { station.visible = false; mode = "line"; }
    }

    // Coaches roll and bob a little with speed, each with its own phase; nothing when standing.
    const k = still ? 0 : clamp(vis / 5, 0, 1);
    coaches.forEach((c, i) => {
      c.rotation.z = Math.sin(now / 820 + i * 1.7) * 0.007 * k;
      c.position.y = Math.sin(now / 170 + i * 2.3) * 0.014 * k;
    });
    panto.position.y = 2.98 + coaches[0].position.y;

    // Camera eases towards the platform while arriving / standing, back to the line after leaving.
    const camGoal = mode === "arriving" || mode === "here" ? 1 : 0;
    cam = still ? camGoal : cam + (camGoal - cam) * Math.min(1, dt * 0.7);
    const e = smooth(cam);
    camera.position.set(...POSE_LINE.pos.map((a, i) => mix(a, POSE_STATION.pos[i], e)));
    look.set(...POSE_LINE.look.map((a, i) => mix(a, POSE_STATION.look[i], e)));
    camera.lookAt(look);

    renderer.render(scene, camera);
    raf = requestAnimationFrame(frame);
  };
  raf = requestAnimationFrame(frame);

  const recolour = () => { for (const [m, t] of mats) m.color.set(tone(t)); };
  const offTheme = onTheme(recolour);
  return {
    update({ speedKmh, atStation }) {
      target = clamp(speedKmh || 0, 0, 200) * KMH;
      const now = performance.now();
      if (atStation) {
        if (stationName !== atStation) drawSign(atStation);
        stationName = atStation;
        if (mode === "line" || mode === "leaving") {
          if (first || reducedMotion()) { // already standing when the screen opened: no arrival to show
            station.position.z = 0; station.visible = true; setOpacity(1); vis = 0; mode = "here";
            cam = 1;
          } else startArrival(now);
        }
      } else if (mode === "here" || mode === "arriving") {
        setOpacity(1);
        mode = "leaving";
        leftAt = now;
      }
      first = false;
    },
    dispose() {
      cancelAnimationFrame(raf);
      ro.disconnect();
      offTheme();
      renderer.dispose();
    },
  };
}
