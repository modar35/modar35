/* ============================================================
   🎄 Ёлка-симулятор 2026 → 2027
   Новогодняя 3D-песочница на Three.js (локальная сборка, без CDN)
   ============================================================ */
import * as THREE from './js/three.module.min.js';

/* ---------------- Утилиты ---------------- */
const rand = (a, b) => a + Math.random() * (b - a);
const randInt = (a, b) => Math.floor(rand(a, b + 1));
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/* ---------------- Палитры ---------------- */
const BALL_COLORS  = [0xff3b3b, 0xffc93f, 0x3f8cff, 0xe8f0f8, 0xff6fb3, 0xa06bff];
const GAR_COLORS   = [0xff5252, 0xffd24d, 0x53ff9a, 0x4dd8ff, 0xb06bff, 0xff8ae0];
const GIFT_COLORS  = [
  { box: 0xd43a3a, rib: 0xffe08a },
  { box: 0x2f6fd4, rib: 0xffffff },
  { box: 0x2f9e44, rib: 0xffd24d },
  { box: 0x8a46d4, rib: 0x9ae6ff },
  { box: 0xd4881f, rib: 0xd43a3a },
];

/* ---------------- Звук (WebAudio, всё синтезируется на лету) ---------------- */
const Sfx = {
  ctx: null, master: null, musicGain: null, noiseBuf: null,
  musicOn: false, _musicTimer: null, _evPtr: 0, _loopStart: 0,

  ensure() {
    if (this.ctx) { if (this.ctx.state === 'suspended') this.ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ctx = new AC();
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.9;
    this.master.connect(this.ctx.destination);
    this.musicGain = this.ctx.createGain();
    this.musicGain.gain.value = 0.85;
    this.musicGain.connect(this.master);
    const len = this.ctx.sampleRate;
    this.noiseBuf = this.ctx.createBuffer(1, len, this.ctx.sampleRate);
    const d = this.noiseBuf.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
  },

  tone(freq, t0, dur, { type = 'sine', vol = 0.1, dest = null, sweepTo = null } = {}) {
    if (!this.ctx) return;
    const o = this.ctx.createOscillator();
    const g = this.ctx.createGain();
    o.type = type;
    o.frequency.setValueAtTime(freq, t0);
    if (sweepTo) o.frequency.exponentialRampToValueAtTime(Math.max(sweepTo, 1), t0 + dur);
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(vol, t0 + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    o.connect(g); g.connect(dest || this.master);
    o.start(t0); o.stop(t0 + dur + 0.05);
  },

  now() { return this.ctx ? this.ctx.currentTime : 0; },

  ding()  { if (!this.ctx) return; const t = this.now(); this.tone(880, t, 0.45, { vol: 0.12 }); this.tone(1318, t, 0.35, { vol: 0.05 }); },
  sparkle(){ if (!this.ctx) return; const t = this.now(); [1174, 1568, 2093].forEach((f, i) => this.tone(f, t + i * 0.07, 0.3, { vol: 0.08 })); },
  star()  { if (!this.ctx) return; const t = this.now(); [1046, 1318, 1568, 2093].forEach((f) => this.tone(f, t, 1.1, { vol: 0.07 })); },
  thud()  { if (!this.ctx) return; const t = this.now(); this.tone(130, t, 0.22, { vol: 0.25, sweepTo: 55 }); this.tone(1200, t + 0.05, 0.25, { vol: 0.05 }); },
  sweep() { if (!this.ctx) return; const t = this.now(); this.tone(520, t, 0.28, { type: 'sawtooth', vol: 0.06, sweepTo: 150 }); },
  whistle(){ if (!this.ctx) return; const t = this.now(); this.tone(800, t, 0.7, { vol: 0.045, sweepTo: 2300 }); },
  boom()  {
    if (!this.ctx) return;
    const t = this.now();
    const src = this.ctx.createBufferSource(); src.buffer = this.noiseBuf;
    const f = this.ctx.createBiquadFilter(); f.type = 'lowpass';
    f.frequency.setValueAtTime(2600, t);
    f.frequency.exponentialRampToValueAtTime(90, t + 0.9);
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0.5, t);
    g.gain.exponentialRampToValueAtTime(0.001, t + 1.1);
    src.connect(f); f.connect(g); g.connect(this.master);
    src.start(t); src.stop(t + 1.2);
    this.tone(65, t, 0.4, { vol: 0.3, sweepTo: 40 });
  },
  fanfare() {
    if (!this.ctx) return;
    const t = this.now();
    [523, 659, 784, 1046].forEach((f, i) => {
      this.tone(f, t + i * 0.1, 0.5, { type: 'square', vol: 0.06 });
      this.tone(f * 2, t + i * 0.1, 0.4, { vol: 0.025 });
    });
  },

  /* ---- Музыка: чиптюн «Jingle Bells» (припев), 16 тактов ---- */
  // [MIDI-нота, длительность в долях]
  _song: [
    [76,1],[76,1],[76,2], [76,1],[76,1],[76,2], [76,1],[79,1],[72,1],[74,1], [76,4],
    [77,1],[77,1],[77,1],[77,1], [77,1],[76,1],[76,1],[76,.5],[76,.5], [76,1],[74,1],[74,1],[76,1], [74,2],[79,2],
    [76,1],[76,1],[76,2], [76,1],[76,1],[76,2], [76,1],[79,1],[72,1],[74,1], [76,4],
    [77,1],[77,1],[77,1],[77,1], [77,1],[76,1],[76,1],[76,.5],[76,.5], [79,1],[79,1],[77,1],[74,1], [72,4],
  ],
  _bassRoots: [0,0,0,0, 5,5,0,7, 0,0,0,0, 5,5,7,0], // от C3, по тактам
  _bpm: 116,

  startMusic() {
    if (!this.ctx || this.musicOn) return;
    this.musicOn = true;
    const spb = 60 / this._bpm;
    // единая лента событий: мелодия + бас («ум-па»), один указатель, бесконечный цикл
    this._events = [];
    let beat = 0;
    for (const [midi, beats] of this._song) {
      this._events.push({ beat, midi, dur: beats, bass: false });
      beat += beats;
    }
    this._totalBeats = beat;
    this._bassRoots.forEach((root, m) => {
      for (let b = 0; b < 4; b++) {
        this._events.push({ beat: m * 4 + b, midi: 48 + (b % 2 ? root + 7 : root), dur: 0.9, bass: true });
      }
    });
    this._events.sort((a, b) => a.beat - b.beat || (a.bass ? 1 : 0) - (b.bass ? 1 : 0));
    this._ptr = 0;
    this._loopStart = this.now() + 0.12;

    const tick = () => {
      if (!this.musicOn) return;
      const ahead = this.now() + 0.2;
      let guard = 0;
      while (guard++ < 200) {
        if (this._ptr >= this._events.length) {
          this._ptr = 0;
          this._loopStart += this._totalBeats * spb;
        }
        const ev = this._events[this._ptr];
        const t = this._loopStart + ev.beat * spb;
        if (t > ahead) break;
        if (t > this.now() - 0.05) {
          const f = 440 * Math.pow(2, (ev.midi - 69) / 12);
          if (ev.bass) {
            this.tone(f, t, ev.dur * spb, { type: 'triangle', vol: 0.075, dest: this.musicGain });
          } else {
            const dur = Math.min(ev.dur * spb * 0.92, 0.6);
            this.tone(f, t, dur, { type: 'square', vol: 0.055, dest: this.musicGain });
            this.tone(f * 2, t, Math.min(dur, 0.4), { vol: 0.016, dest: this.musicGain });
          }
        }
        this._ptr++;
      }
    };
    this._musicTimer = setInterval(tick, 40);
  },
  stopMusic() {
    this.musicOn = false;
    if (this._musicTimer) { clearInterval(this._musicTimer); this._musicTimer = null; }
  },
};

/* ---------------- Сцена ---------------- */
const canvas = document.getElementById('scene');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.setSize(window.innerWidth, window.innerHeight);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0b1226);
scene.fog = new THREE.Fog(0x121f3c, 24, 70);

const camera = new THREE.PerspectiveCamera(50, window.innerWidth / window.innerHeight, 0.1, 220);

/* Свет */
scene.add(new THREE.HemisphereLight(0x93b8ff, 0x1a2438, 0.85));
const moonLight = new THREE.DirectionalLight(0xcfe0ff, 1.15);
moonLight.position.set(8, 14, -6);
scene.add(moonLight);
scene.add(new THREE.AmbientLight(0x2a3550, 0.55));
const boomLight = new THREE.PointLight(0xffffff, 0, 34, 2); // вспышка от салюта
scene.add(boomLight);

/* Небо-градиент */
const sky = new THREE.Mesh(
  new THREE.SphereGeometry(95, 24, 16),
  new THREE.ShaderMaterial({
    side: THREE.BackSide, depthWrite: false, fog: false,
    uniforms: {
      top: { value: new THREE.Color(0x060b1d) },
      bottom: { value: new THREE.Color(0x1c2f55) },
    },
    vertexShader: `varying vec3 vP; void main(){ vP = position; gl_Position = projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
    fragmentShader: `uniform vec3 top; uniform vec3 bottom; varying vec3 vP;
      void main(){ float h = normalize(vP).y; gl_FragColor = vec4(mix(bottom, top, pow(max(h, 0.0), 0.55)), 1.0); }`,
  })
);
scene.add(sky);

/* Звёзды (два слоя мерцания) */
function makeStars(count, size, opacity) {
  const pos = new Float32Array(count * 3);
  for (let i = 0; i < count; i++) {
    const az = rand(0, Math.PI * 2), el = rand(0.06, Math.PI / 2.1), r = 88;
    pos[i * 3] = r * Math.cos(el) * Math.cos(az);
    pos[i * 3 + 1] = r * Math.sin(el);
    pos[i * 3 + 2] = r * Math.cos(el) * Math.sin(az);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const m = new THREE.PointsMaterial({ color: 0xdfe8ff, size, transparent: true, opacity, fog: false, sizeAttenuation: true });
  const p = new THREE.Points(g, m);
  scene.add(p);
  return m;
}
const starsA = makeStars(420, 0.75, 0.95);
const starsB = makeStars(380, 0.45, 0.6);

/* Луна со свечением */
function glowTexture(inner, outer) {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const x = c.getContext('2d');
  const g = x.createRadialGradient(128, 128, 8, 128, 128, 128);
  g.addColorStop(0, inner); g.addColorStop(1, outer);
  x.fillStyle = g; x.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}
const moon = new THREE.Mesh(new THREE.SphereGeometry(2.1, 24, 24), new THREE.MeshBasicMaterial({ color: 0xfdf7dc, fog: false }));
moon.position.set(-26, 22, -38);
scene.add(moon);
const moonGlow = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTexture('rgba(250,244,214,0.85)', 'rgba(250,244,214,0)'), transparent: true, opacity: 0.55, depthWrite: false, fog: false }));
moonGlow.scale.set(17, 17, 1);
moonGlow.position.copy(moon.position);
scene.add(moonGlow);

/* Сияющая надпись «2027» */
function signSprite(text) {
  const c = document.createElement('canvas'); c.width = 1024; c.height = 512;
  const x = c.getContext('2d');
  x.font = '900 300px "Arial Black", Arial, sans-serif';
  x.textAlign = 'center'; x.textBaseline = 'middle';
  x.shadowColor = '#ffb200'; x.shadowBlur = 55;
  const g = x.createLinearGradient(0, 80, 0, 430);
  g.addColorStop(0, '#fff3c4'); g.addColorStop(0.55, '#ffd24d'); g.addColorStop(1, '#ff9d00');
  x.fillStyle = g;
  x.strokeStyle = 'rgba(90,50,0,.9)'; x.lineWidth = 10;
  x.strokeText(text, 512, 268);
  x.fillText(text, 512, 268);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, transparent: true, depthWrite: false, fog: false }));
  s.scale.set(11.5, 5.75, 1);
  return s;
}
const sign = signSprite('2027');
sign.position.set(0, 7.6, -13.5);
scene.add(sign);

/* Земля */
const groundMesh = new THREE.Mesh(
  new THREE.CircleGeometry(62, 48),
  new THREE.MeshStandardMaterial({ color: 0xe9f1fb, roughness: 0.97, metalness: 0 })
);
groundMesh.rotation.x = -Math.PI / 2;
scene.add(groundMesh);

/* Сугробы */
for (let i = 0; i < 8; i++) {
  const drift = new THREE.Mesh(
    new THREE.SphereGeometry(1, 18, 12),
    new THREE.MeshStandardMaterial({ color: 0xf2f7ff, roughness: 0.95 })
  );
  const a = rand(0, Math.PI * 2), r = rand(7, 22);
  drift.position.set(Math.cos(a) * r, rand(-0.25, -0.1), Math.sin(a) * r);
  drift.scale.set(rand(1.6, 3.4), rand(0.5, 1.0), rand(1.2, 2.4));
  scene.add(drift);
}

/* Подсветка-тень под объектом */
function fakeShadow(radius, opacity) {
  const m = new THREE.Mesh(
    new THREE.CircleGeometry(radius, 32),
    new THREE.MeshBasicMaterial({ color: 0x0c1830, transparent: true, opacity, depthWrite: false })
  );
  m.rotation.x = -Math.PI / 2;
  m.position.y = 0.012;
  return m;
}

/* Ель для декораций */
function makeFir(s = 1) {
  const g = new THREE.Group();
  const green = new THREE.MeshStandardMaterial({ color: 0x155f31, roughness: 0.9, flatShading: true });
  const snowM = new THREE.MeshStandardMaterial({ color: 0xf4f8ff, roughness: 0.9 });
  const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.13 * s, 0.18 * s, 0.6 * s, 8),
    new THREE.MeshStandardMaterial({ color: 0x6b4a2e, roughness: 1 }));
  trunk.position.y = 0.3 * s;
  g.add(trunk);
  const layers = [[1.05, 1.5, 0.95], [0.8, 1.3, 1.72], [0.52, 1.1, 2.42]];
  for (const [r, h, y] of layers) {
    const cone = new THREE.Mesh(new THREE.ConeGeometry(r * s, h * s, 9), green);
    cone.position.y = y * s;
    cone.rotation.y = rand(0, Math.PI);
    g.add(cone);
    const capH = 0.32 * h * s, capR = capH * 2 * r / h;
    const cap = new THREE.Mesh(new THREE.ConeGeometry(capR, capH, 9), snowM);
    cap.position.y = (y + h / 2 - capH / 2 / 1) * s;
    cap.position.y = (y * s + (h * s) / 2 - capH / 2);
    cap.rotation.y = cone.rotation.y;
    g.add(cap);
  }
  return g;
}
for (let i = 0; i < 10; i++) {
  const a = (i / 10) * Math.PI * 2 + rand(-0.25, 0.25);
  const r = rand(12.5, 22);
  const fir = makeFir(rand(0.9, 1.9));
  fir.position.set(Math.cos(a) * r, 0, Math.sin(a) * r);
  fir.add(fakeShadow(1.3 * fir.scale.x, 0.10));
  scene.add(fir);
}

/* Снеговик */
function makeSnowman() {
  const g = new THREE.Group();
  const white = new THREE.MeshStandardMaterial({ color: 0xf4f8ff, roughness: 0.9 });
  const sizes = [[0.85, 0.85], [0.58, 1.95], [0.4, 2.72]];
  for (const [r, y] of sizes) {
    const s = new THREE.Mesh(new THREE.SphereGeometry(r, 20, 16), white);
    s.position.y = y; g.add(s);
  }
  const coal = new THREE.MeshStandardMaterial({ color: 0x14161c, roughness: 0.5 });
  for (const dx of [-0.13, 0.13]) {
    const e = new THREE.Mesh(new THREE.SphereGeometry(0.045, 8, 8), coal);
    e.position.set(dx, 2.82, 0.35); g.add(e);
  }
  const nose = new THREE.Mesh(new THREE.ConeGeometry(0.06, 0.34, 10),
    new THREE.MeshStandardMaterial({ color: 0xff8c1a, roughness: 0.7 }));
  nose.rotation.x = Math.PI / 2;
  nose.position.set(0, 2.76, 0.52); g.add(nose);
  for (const y of [1.82, 2.0, 2.18]) {
    const b = new THREE.Mesh(new THREE.SphereGeometry(0.05, 8, 8), coal);
    b.position.set(0, y, 0.56); g.add(b);
  }
  const wood = new THREE.MeshStandardMaterial({ color: 0x6b4a2e, roughness: 1 });
  for (const side of [-1, 1]) {
    const arm = new THREE.Mesh(new THREE.CylinderGeometry(0.032, 0.05, 1.15, 6), wood);
    arm.position.set(side * 0.82, 2.12, 0);
    arm.rotation.z = side * 1.05;
    g.add(arm);
  }
  const scarf = new THREE.Mesh(new THREE.TorusGeometry(0.4, 0.1, 10, 24),
    new THREE.MeshStandardMaterial({ color: 0xd43a3a, roughness: 0.85 }));
  scarf.rotation.x = Math.PI / 2;
  scarf.position.y = 2.42; g.add(scarf);
  const hat = new THREE.Mesh(new THREE.CylinderGeometry(0.27, 0.27, 0.38, 14),
    new THREE.MeshStandardMaterial({ color: 0x27313f, roughness: 0.8 }));
  hat.position.y = 3.1; g.add(hat);
  g.add(fakeShadow(1.5, 0.12));
  return g;
}
const snowman = makeSnowman();
snowman.position.set(5.4, 0, 3.4);
snowman.rotation.y = -0.6;
scene.add(snowman);

/* ---------------- Главная ёлка ---------------- */
const treeGroup = new THREE.Group();
scene.add(treeGroup);
const treeMeshes = [];
const greenMat = new THREE.MeshStandardMaterial({ color: 0x1a7a3c, roughness: 0.88, flatShading: true });
const snowMat = new THREE.MeshStandardMaterial({ color: 0xf6faff, roughness: 0.92 });

const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.22, 0.3, 1.2, 10),
  new THREE.MeshStandardMaterial({ color: 0x6b4a2e, roughness: 1 }));
trunk.position.y = 0.9;
treeGroup.add(trunk);

/* Ведро */
const bucket = new THREE.Mesh(new THREE.CylinderGeometry(0.62, 0.45, 0.75, 18),
  new THREE.MeshStandardMaterial({ color: 0xb8322f, roughness: 0.6, metalness: 0.15 }));
bucket.position.y = 0.38;
treeGroup.add(bucket);
const bucketRim = new THREE.Mesh(new THREE.TorusGeometry(0.62, 0.06, 10, 24),
  new THREE.MeshStandardMaterial({ color: 0xffc93f, roughness: 0.35, metalness: 0.7 }));
bucketRim.rotation.x = Math.PI / 2;
bucketRim.position.y = 0.75;
treeGroup.add(bucketRim);

/* Ярусы кроны */
const LAYERS = [
  { y: 2.0, r: 2.35, h: 2.6 },
  { y: 3.15, r: 1.95, h: 2.2 },
  { y: 4.2, r: 1.55, h: 1.9 },
  { y: 5.15, r: 1.15, h: 1.6 },
  { y: 6.0, r: 0.8, h: 1.4 },
];
LAYERS.forEach((L, i) => {
  const cone = new THREE.Mesh(new THREE.ConeGeometry(L.r, L.h, 13), greenMat);
  cone.position.y = L.y;
  cone.rotation.y = i * 0.5;
  treeGroup.add(cone);
  treeMeshes.push(cone);
  const capH = L.h * 0.3, capR = capH * 2 * L.r / L.h;
  const cap = new THREE.Mesh(new THREE.ConeGeometry(capR, capH, 13), snowMat);
  cap.position.y = L.y + L.h / 2 - capH / 2;
  cap.rotation.y = cone.rotation.y;
  treeGroup.add(cap);
});
treeGroup.add(fakeShadow(2.7, 0.17));
const TREE_TOP_Y = 7.0; // верхушка (последний ярус: 6.0 + 1.4/2)

/* ---------------- Декор ---------------- */
const decorGroup = new THREE.Group();
scene.add(decorGroup);

const ballGeo = new THREE.SphereGeometry(0.14, 18, 14);
const ballMats = BALL_COLORS.map((c) => new THREE.MeshStandardMaterial({ color: c, roughness: 0.25, metalness: 0.55 }));
const capGeo = new THREE.CylinderGeometry(0.045, 0.06, 0.075, 10);
const capMat = new THREE.MeshStandardMaterial({ color: 0xcfd6df, roughness: 0.3, metalness: 0.8 });
const lightGeo = new THREE.SphereGeometry(0.055, 10, 8);
const UP = new THREE.Vector3(0, 1, 0);

function placeBall(point, normal, colorIdx = -1, silent = false) {
  const c = colorIdx >= 0 ? colorIdx : randInt(0, BALL_COLORS.length - 1);
  const g = new THREE.Group();
  const ball = new THREE.Mesh(ballGeo, ballMats[c]);
  ball.rotation.y = rand(0, Math.PI * 2);
  g.add(ball);
  const cap = new THREE.Mesh(capGeo, capMat);
  cap.position.y = 0.165;
  g.add(cap);
  g.position.copy(point).addScaledVector(normal, 0.1); // шар сидит на поверхности
  g.quaternion.setFromUnitVectors(UP, normal);
  g.userData = { t: 'ball', c };
  decorGroup.add(g);
  if (!silent) { Sfx.ding(); popAt('+2%'); }
  updateScore();
  return g;
}

/* Радиус силуэта кроны на высоте y — ступенчатый профиль ярусов (объединение конусов) */
function crownR(y) {
  let r = 0;
  for (const L of LAYERS) {
    const yb = L.y - L.h / 2, ya = L.y + L.h / 2;
    if (y >= yb - 0.05 && y <= ya) r = Math.max(r, L.r * (ya - y) / L.h);
  }
  return r;
}
const envR = (y) => crownR(y);

const garlands = []; // { group, mats[], phase, colorIdx }
function addGarland(colorIdx = -1, silent = false) {
  if (garlands.length >= 8) { toast('Гирлянд уже достаточно — ёлка светится! 💡'); return null; }
  const idx = garlands.length;
  const c = colorIdx >= 0 ? colorIdx : idx % GAR_COLORS.length;
  const g = new THREE.Group();
  const mats = [];
  const SEG = 6, PER = 15;
  for (let s = 0; s < SEG; s++) {
    mats.push(new THREE.MeshStandardMaterial({
      color: 0x10131a, emissive: GAR_COLORS[c], emissiveIntensity: 1.6, roughness: 0.4,
    }));
  }
  for (let i = 0; i < SEG * PER; i++) {
    const t = i / (SEG * PER - 1);
    const y = 1.15 + t * (6.25 - 1.15);
    const ang = idx * 1.7 + i * 0.16 + Math.sin(i * 0.05) * 0.6;
    const r = envR(y) + 0.11;
    const light = new THREE.Mesh(lightGeo, mats[Math.floor(i / PER)]);
    light.position.set(Math.cos(ang) * r, y, Math.sin(ang) * r);
    g.add(light);
  }
  g.userData = { t: 'gar' };
  decorGroup.add(g);
  garlands.push({ group: g, mats, phase: rand(0, Math.PI * 2), colorIdx: c });
  if (!silent) { Sfx.sparkle(); popAt('+7%'); }
  updateScore();
  return g;
}

let star = null; // { group, light }
function makeStarGeo() {
  const shape = new THREE.Shape();
  const out = 0.34, inn = 0.14;
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? out : inn;
    const a = (i / 10) * Math.PI * 2 + Math.PI / 2;
    const x = Math.cos(a) * r, y = Math.sin(a) * r;
    if (i === 0) shape.moveTo(x, y); else shape.lineTo(x, y);
  }
  shape.closePath();
  const geo = new THREE.ExtrudeGeometry(shape, { depth: 0.1, bevelEnabled: true, bevelThickness: 0.03, bevelSize: 0.03, bevelSegments: 2 });
  geo.center();
  return geo;
}
const starGeo = makeStarGeo();
function placeStar(silent = false) {
  if (star) return;
  const g = new THREE.Group();
  const m = new THREE.Mesh(starGeo, new THREE.MeshStandardMaterial({
    color: 0xffd75e, emissive: 0xffb300, emissiveIntensity: 1.3, metalness: 0.6, roughness: 0.3,
  }));
  g.add(m);
  const light = new THREE.PointLight(0xffc24d, 24, 11, 2);
  g.add(light);
  g.position.set(0, TREE_TOP_Y + 0.32, 0);
  g.userData = { t: 'star' };
  decorGroup.add(g);
  star = { group: g, light };
  if (!silent) { Sfx.star(); popAt('+10%'); }
  updateScore();
}

const giftGeo = new THREE.BoxGeometry(1, 1, 1);
const gifts = []; // счётчик размещённых подарков
function placeGift(point, silent = false, preset = null, presetSize = null) {
  if (gifts.length >= 20) { toast('Подарков уже целая горка! 🎁'); return null; }
  const p = new THREE.Vector3(point.x, 0, point.z);
  let len = Math.hypot(p.x, p.z);
  if (len > 4.4) { p.multiplyScalar(4.4 / len); len = 4.4; }
  if (len < 0.95) { // не даём положить подарок внутрь ведра
    const ang = Math.atan2(p.z, p.x);
    p.set(Math.cos(ang) * 0.95, 0, Math.sin(ang) * 0.95);
  }
  const c = preset !== null ? preset : randInt(0, GIFT_COLORS.length - 1);
  const col = GIFT_COLORS[c];
  const s = presetSize || rand(0.5, 0.85);
  const g = new THREE.Group();
  const boxMat = new THREE.MeshStandardMaterial({ color: col.box, roughness: 0.55 });
  const ribMat = new THREE.MeshStandardMaterial({ color: col.rib, roughness: 0.4, metalness: 0.3 });
  const box = new THREE.Mesh(giftGeo, boxMat);
  box.scale.set(s, s * 0.8, s);
  g.add(box);
  const rib1 = new THREE.Mesh(giftGeo, ribMat);
  rib1.scale.set(s * 0.16, s * 0.82, s * 1.02);
  g.add(rib1);
  const rib2 = new THREE.Mesh(giftGeo, ribMat);
  rib2.scale.set(s * 1.02, s * 0.82, s * 0.16);
  g.add(rib2);
  for (const dx of [-1, 1]) {
    const bow = new THREE.Mesh(new THREE.SphereGeometry(s * 0.13, 10, 8), ribMat);
    bow.position.set(dx * s * 0.12, s * 0.45, 0);
    bow.scale.set(1.4, 0.8, 1);
    g.add(bow);
  }
  const ry = rand(0, Math.PI * 2);
  g.position.set(p.x, s * 0.4, p.z);
  g.rotation.y = ry;
  g.userData = { t: 'gift', c };
  decorGroup.add(g);
  gifts.push(g);
  if (!silent) { Sfx.thud(); popAt('+3%'); }
  updateScore();
  return g;
}

function removeDecor(obj) {
  const t = obj.userData.t;
  if (t === 'gar') {
    const i = garlands.findIndex((x) => x.group === obj);
    if (i >= 0) garlands.splice(i, 1);
  }
  if (t === 'gift') {
    const i = gifts.indexOf(obj);
    if (i >= 0) gifts.splice(i, 1);
  }
  if (t === 'star') star = null;
  decorGroup.remove(obj);
  Sfx.sweep();
  updateScore();
  save();
}

/* ---------------- Салют ---------------- */
const rockets = []; // { mesh, vel }
const booms = [];   // { points, vel[], age, life, mat }
function launchFirework() {
  Sfx.whistle();
  const mesh = new THREE.Mesh(
    new THREE.SphereGeometry(0.07, 8, 8),
    new THREE.MeshBasicMaterial({ color: 0xffe9a8, fog: false })
  );
  mesh.position.set(rand(-6, 6), 0.3, rand(-6, 6));
  scene.add(mesh);
  rockets.push({ mesh, vel: new THREE.Vector3(rand(-0.9, 0.9), rand(6.8, 8.6), rand(-0.9, 0.9)) });
}
function explode(p) {
  const hue = Math.random();
  const color = new THREE.Color().setHSL(hue, 0.9, 0.62);
  const N = 110;
  const pos = new Float32Array(N * 3);
  const vel = [];
  for (let i = 0; i < N; i++) {
    pos[i * 3] = p.x; pos[i * 3 + 1] = p.y; pos[i * 3 + 2] = p.z;
    const dir = new THREE.Vector3(rand(-1, 1), rand(-1, 1), rand(-1, 1)).normalize();
    vel.push(dir.multiplyScalar(rand(1.8, 5.4)));
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const mat = new THREE.PointsMaterial({
    color, size: 0.17, transparent: true, opacity: 1,
    blending: THREE.AdditiveBlending, depthWrite: false, fog: false,
  });
  const pts = new THREE.Points(geo, mat);
  scene.add(pts);
  booms.push({ points: pts, vel, age: 0, life: 1.55, mat });
  Sfx.boom();
  boomLight.color.copy(color);
  boomLight.position.copy(p);
  boomLight.intensity = 170;
}
function updateFireworks(dt) {
  for (let i = rockets.length - 1; i >= 0; i--) {
    const r = rockets[i];
    r.vel.y -= 2.4 * dt;
    r.mesh.position.addScaledVector(r.vel, dt);
    if (r.vel.y <= 0.9) {
      scene.remove(r.mesh);
      r.mesh.geometry.dispose(); r.mesh.material.dispose();
      rockets.splice(i, 1);
      explode(r.mesh.position.clone());
    }
  }
  for (let i = booms.length - 1; i >= 0; i--) {
    const b = booms[i];
    b.age += dt;
    const pos = b.points.geometry.attributes.position;
    for (let k = 0; k < b.vel.length; k++) {
      const v = b.vel[k];
      pos.array[k * 3] += v.x * dt;
      pos.array[k * 3 + 1] += v.y * dt;
      pos.array[k * 3 + 2] += v.z * dt;
      v.multiplyScalar(1 - 0.6 * dt);
      v.y -= 1.4 * dt;
    }
    pos.needsUpdate = true;
    b.mat.opacity = Math.max(0, 1 - b.age / b.life);
    if (b.age >= b.life) {
      scene.remove(b.points);
      b.points.geometry.dispose(); b.mat.dispose();
      booms.splice(i, 1);
    }
  }
  boomLight.intensity *= Math.pow(0.02, dt);
}

/* ---------------- Снегопад ---------------- */
const SNOW_N = 1300;
const snowPos = new Float32Array(SNOW_N * 3);
const snowVel = new Float32Array(SNOW_N);
const snowPh = new Float32Array(SNOW_N);
for (let i = 0; i < SNOW_N; i++) {
  snowPos[i * 3] = rand(-38, 38);
  snowPos[i * 3 + 1] = rand(0, 28);
  snowPos[i * 3 + 2] = rand(-38, 38);
  snowVel[i] = rand(1.1, 2.3);
  snowPh[i] = rand(0, Math.PI * 2);
}
const snowGeo = new THREE.BufferGeometry();
snowGeo.setAttribute('position', new THREE.BufferAttribute(snowPos, 3));
const snow = new THREE.Points(snowGeo, new THREE.PointsMaterial({
  color: 0xffffff, size: 0.1, transparent: true, opacity: 0.85, depthWrite: false,
}));
scene.add(snow);
function updateSnow(dt, t) {
  for (let i = 0; i < SNOW_N; i++) {
    snowPos[i * 3 + 1] -= snowVel[i] * dt;
    snowPos[i * 3] += Math.sin(t * 0.7 + snowPh[i]) * 0.28 * dt;
    if (snowPos[i * 3 + 1] < 0) {
      snowPos[i * 3 + 1] = rand(24, 28);
      snowPos[i * 3] = rand(-38, 38);
      snowPos[i * 3 + 2] = rand(-38, 38);
    }
  }
  snowGeo.attributes.position.needsUpdate = true;
}

/* ---------------- Камера (свои орбитальные управление) ---------------- */
const ctl = {
  theta: 0.55, phi: 1.12, radius: 12.5,
  tTheta: 0.55, tPhi: 1.12, tRadius: 12.5,
  target: new THREE.Vector3(0, 3.3, 0),
  auto: true,
};
function updateCamera(dt) {
  if (ctl.auto) ctl.tTheta += dt * 0.07;
  const k = 1 - Math.pow(0.0001, dt);
  ctl.theta += (ctl.tTheta - ctl.theta) * k;
  ctl.phi += (ctl.tPhi - ctl.phi) * k;
  ctl.radius += (ctl.tRadius - ctl.radius) * k;
  const sp = Math.sin(ctl.phi), cp = Math.cos(ctl.phi);
  camera.position.set(
    ctl.target.x + ctl.radius * sp * Math.sin(ctl.theta),
    ctl.target.y + ctl.radius * cp,
    ctl.target.z + ctl.radius * sp * Math.cos(ctl.theta)
  );
  camera.lookAt(ctl.target);
}

const pointers = new Map();
let downX = 0, downY = 0, dragged = false, pinchBase = 0, pinchR0 = 0;
canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 1) { downX = e.clientX; downY = e.clientY; dragged = false; }
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    pinchBase = Math.hypot(a.x - b.x, a.y - b.y);
    pinchR0 = ctl.tRadius;
  }
  ctl.auto = false;
});
canvas.addEventListener('pointermove', (e) => {
  if (!pointers.has(e.pointerId)) return;
  const prev = pointers.get(e.pointerId);
  const dx = e.clientX - prev.x, dy = e.clientY - prev.y;
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (pointers.size === 1) {
    if (Math.hypot(e.clientX - downX, e.clientY - downY) > 7) dragged = true;
    if (dragged) {
      ctl.tTheta -= dx * 0.0052;
      ctl.tPhi = clamp(ctl.tPhi - dy * 0.0042, 0.3, 1.45);
    }
  } else if (pointers.size === 2) {
    const [a, b] = [...pointers.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y);
    if (pinchBase > 0) ctl.tRadius = clamp(pinchR0 * pinchBase / Math.max(d, 20), 6, 26);
  }
});
function endPointer(e) {
  if (pointers.has(e.pointerId)) {
    lastClickX = e.clientX;
    lastClickY = e.clientY;
    pointers.delete(e.pointerId);
    if (pointers.size === 0 && !dragged) handleClick(e);
  }
}
canvas.addEventListener('pointerup', endPointer);
canvas.addEventListener('pointercancel', (e) => pointers.delete(e.pointerId));
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  ctl.auto = false;
  ctl.tRadius = clamp(ctl.tRadius * Math.exp(e.deltaY * 0.0011), 6, 26);
}, { passive: false });
canvas.addEventListener('contextmenu', (e) => e.preventDefault());

/* ---------------- Режимы и клики ---------------- */
let mode = 'ball';
let ballColor = -1; // -1 = случайный

const raycaster = new THREE.Raycaster();
const ndc = new THREE.Vector2();
function handleClick(e) {
  if (!overlay.classList.contains('hidden')) return;
  const rect = canvas.getBoundingClientRect();
  ndc.x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
  ndc.y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(ndc, camera);

  if (mode === 'firework') { launchFirework(); return; }

  if (mode === 'gift') {
    const hit = raycaster.intersectObject(groundMesh)[0];
    if (hit) { placeGift(hit.point); save(); }
    return;
  }

  if (mode === 'erase') {
    const hit = raycaster.intersectObjects(decorGroup.children, true)[0];
    if (hit) {
      let root = hit.object;
      while (root.parent && root.parent !== decorGroup) root = root.parent;
      if (root.parent === decorGroup) removeDecor(root);
    }
    return;
  }

  // ball / garland / star — по кроне
  const hit = raycaster.intersectObjects(treeMeshes)[0];
  if (!hit) return;
  if (mode === 'ball') {
    const balls = decorGroup.children.filter((o) => o.userData.t === 'ball');
    if (balls.length >= 250) { toast('Все шары мира уже на этой ёлке! 😄'); return; }
    const n = hit.face.normal.clone().transformDirection(hit.object.matrixWorld);
    placeBall(hit.point, n, ballColor);
    save();
  } else if (mode === 'garland') {
    if (addGarland()) save();
  } else if (mode === 'star') {
    if (star) { toast('Звезда уже сияет на макушке! ⭐'); return; }
    placeStar();
    save();
  }
}

/* ---------------- UI ---------------- */
const overlay = document.getElementById('overlay');
const banner = document.getElementById('banner');
const toastsEl = document.getElementById('toasts');
const paletteEl = document.getElementById('palette');
const moodFill = document.getElementById('moodFill');
const moodPct = document.getElementById('moodPct');
const countdownEl = document.getElementById('countdown');

const MODE_NAMES = { ball: 'Шары', garland: 'Гирлянда', star: 'Звезда', gift: 'Подарки', firework: 'Салют', erase: 'Убрать' };

document.querySelectorAll('.tool').forEach((btn) => {
  btn.addEventListener('click', () => selectMode(btn.dataset.mode));
});
function selectMode(m) {
  mode = m;
  document.querySelectorAll('.tool').forEach((b) => b.classList.toggle('active', b.dataset.mode === m));
  paletteEl.classList.toggle('hidden', m !== 'ball');
  toast(`Инструмент: ${MODE_NAMES[m]}`);
}
paletteEl.querySelectorAll('.sw').forEach((sw) => {
  sw.addEventListener('click', () => {
    ballColor = parseInt(sw.dataset.c, 10);
    paletteEl.querySelectorAll('.sw').forEach((s) => s.classList.toggle('active', s === sw));
  });
});

document.getElementById('btnStart').addEventListener('click', () => {
  overlay.classList.add('hidden');
  Sfx.ensure();
  if (prefMusic && !Sfx.musicOn) btnMusic.click();
  if (!hadSave) {
    setTimeout(() => toast('Кликайте по ёлке, чтобы вешать шары 🎈'), 800);
    setTimeout(() => toast('Наберите 100% настроения — будет салют! 🎆'), 3400);
  }
});

const btnMusic = document.getElementById('btnMusic');
btnMusic.addEventListener('click', () => {
  Sfx.ensure();
  if (Sfx.musicOn) {
    Sfx.stopMusic();
    btnMusic.classList.add('off');
    btnMusic.textContent = '🎵';
    toast('Музыка выключена 🔇');
  } else {
    Sfx.startMusic();
    btnMusic.classList.remove('off');
    btnMusic.textContent = '🎶';
    toast('Играет «Jingle Bells» 🎶');
  }
  save();
});

document.getElementById('btnShot').addEventListener('click', () => {
  renderer.render(scene, camera);
  canvas.toBlob((blob) => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'elka-2027.png';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast('Скриншот сохранён 📷');
  });
});

document.getElementById('btnReset').addEventListener('click', () => {
  if (!confirm('Нарядить новую ёлку с нуля? Весь прогресс будет удалён.')) return;
  while (decorGroup.children.length) decorGroup.remove(decorGroup.children[0]);
  garlands.length = 0;
  star = null;
  gifts.length = 0;
  localStorage.removeItem('elka3d-save-v1');
  celebrated = false;
  banner.classList.remove('show');
  updateScore();
  Sfx.sweep();
  toast('Новая ёлка готова! Украшайте 🎄');
});

function toast(text) {
  const d = document.createElement('div');
  d.className = 'toast';
  d.textContent = text;
  toastsEl.appendChild(d);
  while (toastsEl.children.length > 3) toastsEl.removeChild(toastsEl.firstChild);
  setTimeout(() => { d.style.opacity = '0'; d.style.transition = 'opacity .4s'; }, 1800);
  setTimeout(() => d.remove(), 2300);
}
function popAt(text) {
  const d = document.createElement('div');
  d.className = 'pop';
  d.textContent = text;
  d.style.left = lastClickX + 'px';
  d.style.top = (lastClickY - 20) + 'px';
  document.body.appendChild(d);
  setTimeout(() => d.remove(), 1000);
}

/* Клавиатура */
const KEY_MODES = { '1': 'ball', '2': 'garland', '3': 'star', '4': 'gift', '5': 'firework', '6': 'erase' };
window.addEventListener('keydown', (e) => {
  if (KEY_MODES[e.key]) selectMode(KEY_MODES[e.key]);
  if (e.key === 'm' || e.key === 'ь') btnMusic.click();
});

/* ---------------- Счёт и празднование ---------------- */
let celebrated = false;
function score() {
  let s = 0;
  for (const o of decorGroup.children) {
    if (o.userData.t === 'ball') s += 2;
    else if (o.userData.t === 'gar') s += 7;
    else if (o.userData.t === 'gift') s += 3;
    else if (o.userData.t === 'star') s += 10;
  }
  return Math.min(100, s);
}
function updateScore() {
  const pct = score();
  moodFill.style.width = pct + '%';
  moodPct.textContent = pct + '%';
  if (pct >= 100 && !celebrated) celebrate();
}
function celebrate() {
  celebrated = true;
  banner.classList.add('show');
  Sfx.fanfare();
  for (let i = 0; i < 14; i++) setTimeout(launchFirework, i * 430 + rand(0, 180));
  setTimeout(() => banner.classList.remove('show'), 7000);
  toast('🏆 Ёлка года наряжена! Можно доделывать детали или нажать 🔄');
}

/* ---------------- Обратный отсчёт до Нового года ---------------- */
function tickCountdown() {
  const now = new Date();
  const y = now.getFullYear();
  // 1 января — сам праздник
  if (now.getMonth() === 0 && now.getDate() === 1) {
    countdownEl.innerHTML = `Сегодня <b>1 января ${y}</b> — С Новым ${y} годом! 🎉`;
    return;
  }
  const target = new Date(y + 1, 0, 1, 0, 0, 0);
  let ms = target - now;
  const d = Math.floor(ms / 86400000); ms -= d * 86400000;
  const h = Math.floor(ms / 3600000); ms -= h * 3600000;
  const m = Math.floor(ms / 60000); ms -= m * 60000;
  const s = Math.floor(ms / 1000);
  const pad = (n) => String(n).padStart(2, '0');
  countdownEl.innerHTML = `До Нового ${y + 1} года: <b>${d} д ${pad(h)}:${pad(m)}:${pad(s)}</b>`;
}
setInterval(tickCountdown, 500);
tickCountdown();

/* ---------------- Сохранение ---------------- */
function save() {
  const data = {
    b: [], g: garlands.map((x) => x.colorIdx),
    s: star ? 1 : 0, f: [],
    music: Sfx.musicOn ? 1 : 0,
  };
  for (const o of decorGroup.children) {
    if (o.userData.t === 'ball') {
      data.b.push({ p: [+o.position.x.toFixed(3), +o.position.y.toFixed(3), +o.position.z.toFixed(3)], c: o.userData.c });
    } else if (o.userData.t === 'gift') {
      data.f.push({
        p: [+o.position.x.toFixed(3), +o.position.z.toFixed(3)],
        ry: +o.rotation.y.toFixed(3),
        s: +o.children[0].scale.x.toFixed(3),
        c: o.userData.c,
      });
    }
  }
  try { localStorage.setItem('elka3d-save-v1', JSON.stringify(data)); } catch (e) { /* приватный режим */ }
}
function load() {
  let data = null;
  try { data = JSON.parse(localStorage.getItem('elka3d-save-v1')); } catch (e) { data = null; }
  if (!data) return false;
  for (const b of data.b || []) {
    const p = new THREE.Vector3(...b.p);
    const n = p.clone().setY(p.y - 0.05).normalize(); // нормаль ~ от оси ёлки
    placeBall(p, n, b.c, true);
  }
  for (const c of data.g || []) addGarland(c, true);
  if (data.s) placeStar(true);
  for (const f of data.f || []) {
    const g = placeGift(new THREE.Vector3(f.p[0], 0, f.p[1]), true, f.c, f.s);
    if (g) g.rotation.y = f.ry;
  }
  prefMusic = !!data.music;
  return true;
}
let prefMusic = false;
const hadSave = load();
if (hadSave) {
  const btn = document.getElementById('btnStart');
  btn.textContent = '▶  Продолжить';
}
updateScore();

/* ---------------- Главный цикл ---------------- */
const clock = new THREE.Clock();
function animate() {
  requestAnimationFrame(animate);
  const dt = Math.min(clock.getDelta(), 0.05);
  const t = clock.elapsedTime;

  updateCamera(dt);
  updateSnow(dt, t);
  updateFireworks(dt);

  // мерцание звёзд
  starsA.opacity = 0.75 + 0.2 * Math.sin(t * 1.3);
  starsB.opacity = 0.5 + 0.25 * Math.sin(t * 0.9 + 2);

  // гирлянды: «бегущие огни»
  for (const gar of garlands) {
    for (let s = 0; s < gar.mats.length; s++) {
      gar.mats[s].emissiveIntensity = 1.15 + 0.95 * Math.sin(t * 3.2 + gar.phase + s * 0.9);
    }
  }

  // звезда на макушке
  if (star) {
    star.group.rotation.y += dt * 0.9;
    const k = 1 + 0.07 * Math.sin(t * 3.1);
    star.group.scale.set(k, k, k);
    star.light.intensity = 22 + 6 * Math.sin(t * 3.1);
  }

  // надпись 2027
  sign.position.y = 7.6 + Math.sin(t * 0.8) * 0.18;
  sign.material.opacity = 0.82 + 0.18 * Math.sin(t * 1.7);

  renderer.render(scene, camera);
}
animate();

/* ---------------- Ресайз ---------------- */
window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});
