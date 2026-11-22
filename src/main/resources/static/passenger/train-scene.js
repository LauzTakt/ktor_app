// The 3D train on its track (Three.js, loaded on demand) shown on the on-tracks screen.

import { $, onTheme, reducedMotion } from "../common.js";
import { THREE_URL, clamp, cssVar, restartBtn, setUrl } from "./ui.js";

export function trainGone(s) {
  setUrl({});
  s.innerHTML = `<div class="finish"><h1>This train has ended its run</h1><p class="lead">It is no longer in service.</p></div>
    <div class="actions"><button class="btn primary block" id="again">Start again</button></div>`;
  $("#again", s).onclick = () => restartBtn.click();
}

/**
 * Three.js scene: always the same train on the same straight track (not the real geometry). The train stays put; the
 * sleepers, poles and ground move under it at a pace that follows the real speed, so a stopped train stands still.
 */
export async function trainScene(canvas) {
  let THREE;
  try { THREE = await import(THREE_URL); } catch { canvas.closest(".scene").classList.add("no3d"); return null; }
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 2, 0.1, 200);
  camera.position.set(9, 6.4, 11);
  camera.lookAt(-0.5, 0.6, -2);

  const mat = (c) => new THREE.MeshLambertMaterial({ color: c });
  const palette = () => ({
    white: cssVar("--white"), milk: cssVar("--milk"), cloud: cssVar("--cloud"), iron: cssVar("--iron"), granite: cssVar("--granite"),
    cement: cssVar("--cement"), charcoal: cssVar("--charcoal"), red: cssVar("--red"),
  });
  let P = palette();
  scene.add(new THREE.HemisphereLight(0xffffff, 0x888888, 1.6));
  const sun = new THREE.DirectionalLight(0xffffff, 1.4);
  sun.position.set(6, 10, 8);
  scene.add(sun);

  const ground = new THREE.Mesh(new THREE.PlaneGeometry(80, 200), mat(P.milk));
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);
  const ballast = new THREE.Mesh(new THREE.BoxGeometry(3.2, 0.2, 200), mat(P.cloud));
  ballast.position.y = 0.1;
  scene.add(ballast);
  for (const x of [-0.72, 0.72]) {
    const rail = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.14, 200), mat(P.iron));
    rail.position.set(x, 0.33, 0);
    scene.add(rail);
  }
  const moving = new THREE.Group();
  scene.add(moving);
  const SPACING = 0.7, COUNT = 120;
  const sleeperMat = mat(P.granite);
  for (let i = 0; i < COUNT; i++) {
    const sl = new THREE.Mesh(new THREE.BoxGeometry(2.2, 0.08, 0.26), sleeperMat);
    sl.position.set(0, 0.24, -60 + i * SPACING);
    moving.add(sl);
  }
  const poleMat = mat(P.cement);
  for (let i = 0; i < 8; i++) {
    const pole = new THREE.Mesh(new THREE.BoxGeometry(0.14, 4.2, 0.14), poleMat);
    pole.position.set(-2.2, 2.1, -60 + i * 10.5);
    const arm = new THREE.Mesh(new THREE.BoxGeometry(2.4, 0.08, 0.08), poleMat);
    arm.position.set(1.15, 1.9, 0);
    pole.add(arm);
    moving.add(pole);
  }
  const wire = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 200), mat(P.granite));
  wire.position.set(0, 4.0, 0);
  scene.add(wire);

  // Train: two coaches, flat SBB colours (white body, red band, dark windows), no logos.
  const train = new THREE.Group();
  const bodyMat = mat(P.white), bandMat = mat(P.red), winMat = mat(P.charcoal), roofMat = mat(P.cement);
  const coach = (z, cab) => {
    const g = new THREE.Group();
    const body = new THREE.Mesh(new THREE.BoxGeometry(1.9, 2.0, 7.2), bodyMat); body.position.y = 1.55; g.add(body);
    const band = new THREE.Mesh(new THREE.BoxGeometry(1.92, 0.22, 7.22), bandMat); band.position.y = 0.95; g.add(band);
    const roof = new THREE.Mesh(new THREE.BoxGeometry(1.7, 0.18, 7.0), roofMat); roof.position.y = 2.64; g.add(roof);
    for (let w = -2.6; w <= 2.6; w += 1.3) {
      const win = new THREE.Mesh(new THREE.BoxGeometry(1.94, 0.6, 0.85), winMat); win.position.set(0, 1.85, w); g.add(win);
    }
    if (cab) {
      const nose = new THREE.Mesh(new THREE.BoxGeometry(1.9, 1.3, 0.9), bodyMat); nose.position.set(0, 1.2, 4.0); g.add(nose);
      const wind = new THREE.Mesh(new THREE.BoxGeometry(1.6, 0.55, 0.92), winMat); wind.position.set(0, 2.05, 3.62); wind.rotation.x = -0.35; g.add(wind);
      const nband = new THREE.Mesh(new THREE.BoxGeometry(1.92, 0.22, 0.92), bandMat); nband.position.set(0, 0.95, 4.0); g.add(nband);
    }
    for (const bz of [-2.6, 2.6]) {
      const bogie = new THREE.Mesh(new THREE.BoxGeometry(1.5, 0.35, 1.6), winMat); bogie.position.set(0, 0.5, bz); g.add(bogie);
    }
    g.position.z = z;
    return g;
  };
  train.add(coach(3.9, true), coach(-3.5, false));
  scene.add(train);

  // Station (platform + small house + name sign), shown while the train stands at a stop. It slides in towards the
  // train when it arrives and moves away with the track when the train leaves.
  const station = new THREE.Group();
  const platMat = mat(P.cloud), wallMat = mat(P.white), roofMat2 = mat(P.iron), doorMat = mat(P.granite), trimMat = mat(P.cement);
  const box = (w, h, d, m, x, y, z) => { const o = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), m); o.position.set(x, y, z); station.add(o); return o; };
  // Platform along the far side of the track, with its white safety line
  box(3.4, 0.55, 26, platMat, -2.95, 0.28, 0);
  box(0.14, 0.02, 26, mat("#ffffff"), -1.55, 0.57, 0);
  // Canopy on slim columns (shows above the train roof)
  box(2.8, 0.14, 15, roofMat2, -2.7, 3.45, 0);
  box(2.84, 0.24, 0.1, trimMat, -2.7, 3.32, 7.5);
  for (const cz of [-6, -2, 2, 6]) box(0.12, 2.9, 0.12, doorMat, -3.2, 2.0, cz);
  // Station building behind the platform: two floors, windows, door, cornice
  box(4.2, 3.6, 9, wallMat, -7.0, 1.8, -3);
  box(4.6, 0.25, 9.4, roofMat2, -7.0, 3.7, -3);
  box(4.3, 0.12, 9.1, trimMat, -7.0, 1.95, -3);
  for (const wz of [-6.4, -4.8, -1.2, 0.4]) {
    box(0.06, 0.8, 0.9, winMat, -4.88, 1.15, wz);
    box(0.06, 0.8, 0.9, winMat, -4.88, 2.8, wz);
  }
  box(0.06, 1.7, 1.2, doorMat, -4.88, 0.85, -3);
  // Lamps and benches on the platform
  for (const lz of [-9, 9]) { box(0.08, 3.0, 0.08, doorMat, -3.9, 1.95, lz); box(0.5, 0.12, 0.25, trimMat, -3.7, 3.45, lz); }
  for (const bz of [-4.5, 4.5]) { box(0.5, 0.08, 1.8, doorMat, -3.8, 0.85, bz); box(0.08, 0.45, 1.8, doorMat, -4.05, 1.1, bz); }
  // Name sign hanging from the canopy edge, facing the camera
  const signCanvas = document.createElement("canvas"); signCanvas.width = 512; signCanvas.height = 96;
  const signTex = new THREE.CanvasTexture(signCanvas);
  signTex.colorSpace = THREE.SRGBColorSpace;
  const sign = new THREE.Mesh(new THREE.PlaneGeometry(3.4, 0.64), new THREE.MeshBasicMaterial({ map: signTex }));
  sign.position.set(-1.5, 4.05, 1.5); sign.rotation.y = 0.69; station.add(sign);
  box(0.05, 0.5, 0.05, doorMat, -1.5, 3.7, 0.4); box(0.05, 0.5, 0.05, doorMat, -1.5, 3.7, 2.6);
  const drawSign = (name) => {
    const g = signCanvas.getContext("2d");
    g.fillStyle = "#2d327d"; g.fillRect(0, 0, 512, 96);
    g.fillStyle = "#ffffff"; g.font = "700 64px 'SBB Web', Helvetica, Arial, sans-serif"; g.textBaseline = "middle";
    let txt = name || ""; while (g.measureText(txt).width > 472 && txt.length > 3) txt = txt.slice(0, -2) + "…";
    g.fillText(txt, 20, 52); signTex.needsUpdate = true;
  };
  station.visible = false;
  scene.add(station);
  let stationMode = "away"; // away | arriving | here | leaving
  let stationName = null;

  let speed = 0, target = 0, raf = 0, t0 = performance.now();
  const resize = () => {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };
  const ro = new ResizeObserver(resize);
  ro.observe(canvas);
  const frame = (now) => {
    const dt = Math.min(0.05, (now - t0) / 1000);
    t0 = now;
    speed += (target - speed) * Math.min(1, dt * 1.5); // ease in / out, no jumps
    const v = reducedMotion() ? 0 : (speed / 3.6) * 0.18; // scaled down: readable, not dizzying
    moving.position.z = (moving.position.z + v * dt) % (SPACING * 15);
    if (stationMode === "arriving" || stationMode === "here") {
      station.position.z += (0 - station.position.z) * Math.min(1, dt * (reducedMotion() ? 60 : 1.8)); // eases in, no bounce
      if (Math.abs(station.position.z) < 0.02) { station.position.z = 0; stationMode = "here"; }
    } else if (stationMode === "leaving") {
      station.position.z += Math.max(v, 4) * dt;
      if (station.position.z > 60) { station.visible = false; stationMode = "away"; }
    }
    train.position.y = speed > 1 && !reducedMotion() ? Math.sin(now / 160) * 0.015 : 0;
    renderer.render(scene, camera);
    raf = requestAnimationFrame(frame);
  };
  raf = requestAnimationFrame(frame);

  const recolour = () => {
    P = palette();
    ground.material.color.set(P.milk); ballast.material.color.set(P.cloud); sleeperMat.color.set(P.granite);
    platMat?.color.set(P.cloud); wallMat?.color.set(P.white === "#17191c" ? "#d2d6db" : "#ffffff"); roofMat2?.color.set(P.iron);
    poleMat.color.set(P.cement); bodyMat.color.set(P.white === "#17191c" ? "#e8e9eb" : P.white); winMat.color.set("#2a2d33");
  };
  recolour();
  const offTheme = onTheme(recolour);
  return {
    update({ speedKmh, atStation }) {
      target = clamp(speedKmh || 0, 0, 200);
      if (atStation && (stationMode === "away" || stationMode === "leaving" || stationName !== atStation)) {
        if (stationName !== atStation) drawSign(atStation);
        stationName = atStation;
        if (stationMode !== "here") { station.position.z = -45; station.visible = true; stationMode = "arriving"; }
      } else if (!atStation && (stationMode === "here" || stationMode === "arriving")) stationMode = "leaving";
    },
    dispose() {
      cancelAnimationFrame(raf);
      ro.disconnect();
      offTheme();
      renderer.dispose();
    },
  };
}
