/* Сумеречный разлом — оригинальный 3D MOBA-прототип. */
(() => {
  "use strict";

  const THREE = window.THREE;
  const $ = (id) => document.getElementById(id);
  const canvas = $("world");
  const isTouchDevice = window.matchMedia ? window.matchMedia("(pointer: coarse)").matches : false;
  const lobby = $("lobby");
  const hud = $("game-hud");
  const feed = $("combat-feed");
  const WORLD_EDGE = 14;
  const BLUE = "blue";
  const RED = "red";
  const HEROES = window.RiftMeta ? window.RiftMeta.HEROES : {};
  let selectedHeroId = window.RiftMeta ? window.RiftMeta.selectedHeroId : "elaris";
  let selectedSkinId = window.RiftMeta ? window.RiftMeta.selectedSkinId : "dawn";
  let activeAbilities = (HEROES[selectedHeroId] || {}).abilities || {
    q: { cooldown: 6, cost: 55, name: "Рассветный осколок", damage: 135 },
    w: { cooldown: 10, cost: 70, name: "Скачок", damage: 105 },
    e: { cooldown: 8, cost: 70, name: "Солнечная сфера", damage: 175 },
    r: { cooldown: 28, cost: 115, name: "Сияние", damage: 245 }
  };
  const defaultHero = (id) => HEROES[id] || HEROES.elaris || { id: "elaris", name: "Эларис", stats: { hp: 780, mana: 500, speed: 3.65, attack: 39, delay: .78, range: 4 }, palette: { glow: 0xffdc87, blade: 0x8dece4, armor: 0xd99bf0, trim: 0xffdf91 }, lines: {} };
  const createCooldownMap = () => Object.keys(activeAbilities).reduce((map, key) => { map[key] = 0; return map; }, {});

  if (!THREE) {
    document.body.innerHTML = '<div style="padding:32px;color:#fff;font:16px sans-serif">Не удалось загрузить 3D-движок. Перезагрузите страницу.</div>';
    return;
  }

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, antialias: !isTouchDevice, alpha: false, powerPreference: "high-performance" });
  } catch (error) {
    document.body.innerHTML = '<div style="padding:32px;color:#fff;font:16px sans-serif">Для игры требуется устройство с поддержкой WebGL.</div>';
    return;
  }

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x0a1714);
  scene.fog = new THREE.FogExp2(0x0b1815, 0.015);
  const camera = new THREE.OrthographicCamera(-12, 12, 12, -12, 0.1, 100);
  const cameraTarget = new THREE.Vector3(1.8, 0, -0.35);
  const clock = new THREE.Clock();
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  const groundPick = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  let ground;
  let menuHero;
  let menuPlatform;
  let showcaseLight;
  let player = null;
  let enemyHero = null;
  let actors = [];
  let structures = [];
  let projectiles = [];
  let effects = [];
  let worldTime = 0;
  let lastWave = -100;
  let waveNumber = 0;
  let nextCleanup = 0;
  let attackHeld = false;
  let ambienceTimer = null;
  let ambienceMaster = null;
  let ambienceStep = 0;
  let joystickInput = { x: 0, z: 0 };
  let moveTarget = null;
  let state = {
    phase: "lobby",
    elapsed: 0,
    blueScore: 0,
    redScore: 0,
    gold: 245,
    kills: 0,
    deaths: 0,
    assists: 0,
    potionUsed: false,
    cooldowns: createCooldownMap()
  };

  const v = (x, y, z) => new THREE.Vector3(x, y, z);
  const clamp = (n, min, max) => Math.max(min, Math.min(max, n));
  const distance = (a, b) => Math.hypot(a.x - b.x, a.z - b.z);
  const randomSeed = (seed) => {
    let t = seed >>> 0;
    return () => {
      t += 0x6D2B79F5;
      let n = t;
      n = Math.imul(n ^ (n >>> 15), n | 1);
      n ^= n + Math.imul(n ^ (n >>> 7), n | 61);
      return ((n ^ (n >>> 14)) >>> 0) / 4294967296;
    };
  };

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, isTouchDevice ? 1.3 : 1.8));
  renderer.shadowMap.enabled = !isTouchDevice;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  if ("outputColorSpace" in renderer && THREE.SRGBColorSpace) renderer.outputColorSpace = THREE.SRGBColorSpace;
  else if (THREE.sRGBEncoding !== undefined) renderer.outputEncoding = THREE.sRGBEncoding;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;

  const hemi = new THREE.HemisphereLight(0xc5dfd3, 0x243029, 0.95);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xffe0a4, 1.45);
  sun.position.set(-13, 24, 10);
  sun.castShadow = !isTouchDevice;
  sun.shadow.mapSize.set(1024, 1024);
  sun.shadow.camera.left = -22;
  sun.shadow.camera.right = 22;
  sun.shadow.camera.top = 22;
  sun.shadow.camera.bottom = -22;
  sun.shadow.camera.near = 1;
  sun.shadow.camera.far = 65;
  sun.shadow.bias = -0.00035;
  scene.add(sun);
  const fillLight = new THREE.DirectionalLight(0x70c8d1, 0.36);
  fillLight.position.set(13, 10, -14);
  scene.add(fillLight);

  function canvasTexture(painter, repeatX, repeatY) {
    const c = document.createElement("canvas");
    c.width = c.height = 256;
    const ctx = c.getContext("2d");
    painter(ctx, c.width, c.height);
    const texture = new THREE.CanvasTexture(c);
    if ("colorSpace" in texture && THREE.SRGBColorSpace) texture.colorSpace = THREE.SRGBColorSpace;
    else if (THREE.sRGBEncoding !== undefined) texture.encoding = THREE.sRGBEncoding;
    texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
    texture.repeat.set(repeatX || 1, repeatY || 1);
    texture.anisotropy = Math.min(4, renderer.capabilities.getMaxAnisotropy());
    return texture;
  }

  const grassMap = canvasTexture((ctx, size) => {
    ctx.fillStyle = "#365741";
    ctx.fillRect(0, 0, size, size);
    const rand = randomSeed(179);
    const colors = ["rgba(11,31,22,.18)", "rgba(144,166,94,.12)", "rgba(8,29,25,.13)", "rgba(190,163,99,.08)"];
    for (let i = 0; i < 1100; i++) {
      ctx.fillStyle = colors[Math.floor(rand() * colors.length)];
      const radius = 1 + rand() * 5;
      ctx.beginPath();
      ctx.ellipse(rand() * size, rand() * size, radius * 1.8, radius, rand() * 3.14, 0, Math.PI * 2);
      ctx.fill();
    }
    for (let i = 0; i < 80; i++) {
      ctx.strokeStyle = "rgba(183,190,125,.14)";
      ctx.lineWidth = 1;
      const x = rand() * size;
      const y = rand() * size;
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x + (rand() - .5) * 9, y - 2 - rand() * 8);
      ctx.stroke();
    }
  }, 10, 10);

  const stoneMap = canvasTexture((ctx, size) => {
    ctx.fillStyle = "#716e58";
    ctx.fillRect(0, 0, size, size);
    const rand = randomSeed(827);
    const rows = 8;
    const rowH = size / rows;
    for (let row = 0; row < rows; row++) {
      let x = row % 2 ? -20 : 0;
      while (x < size) {
        const w = 27 + rand() * 20;
        const shade = 91 + Math.floor(rand() * 36);
        ctx.fillStyle = `rgb(${shade + 8},${shade + 5},${shade - 9})`;
        ctx.fillRect(x + 1.5, row * rowH + 1.5, w - 3, rowH - 3);
        ctx.strokeStyle = "rgba(30,31,26,.52)";
        ctx.strokeRect(x + 1.5, row * rowH + 1.5, w - 3, rowH - 3);
        ctx.fillStyle = "rgba(222,208,167,.12)";
        ctx.fillRect(x + 3, row * rowH + 3, Math.max(4, w - 8), 1);
        x += w;
      }
    }
    for (let i = 0; i < 1000; i++) {
      ctx.fillStyle = rand() > .5 ? "rgba(255,237,184,.035)" : "rgba(15,18,15,.04)";
      ctx.fillRect(rand() * size, rand() * size, 1 + rand() * 2, 1 + rand() * 2);
    }
  }, 1.7, 14);

  const grassMat = new THREE.MeshStandardMaterial({ map: grassMap, color: 0xffffff, roughness: 1 });
  const pathMat = new THREE.MeshStandardMaterial({ map: stoneMap, color: 0xd0c7a6, roughness: 0.96 });
  const trimMat = new THREE.MeshStandardMaterial({ color: 0x493e32, roughness: 0.9 });
  const stoneMat = new THREE.MeshStandardMaterial({ color: 0x697567, roughness: 0.94 });
  const darkStoneMat = new THREE.MeshStandardMaterial({ color: 0x303b37, roughness: 0.98 });
  const waterMat = new THREE.MeshStandardMaterial({ color: 0x28777a, roughness: 0.3, metalness: 0.12, transparent: true, opacity: 0.88, emissive: 0x073536, emissiveIntensity: 0.4 });

  function makeGround() {
    ground = new THREE.Mesh(new THREE.PlaneGeometry(35, 35), grassMat);
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.14;
    ground.receiveShadow = true;
    ground.name = "arena-ground";
    scene.add(ground);

    const border = new THREE.Mesh(new THREE.BoxGeometry(35.4, 0.22, 35.4), darkStoneMat);
    border.position.y = -0.29;
    border.receiveShadow = true;
    scene.add(border);

    const laneGroup = new THREE.Group();
    laneGroup.rotation.y = Math.PI / 4;
    const lane = new THREE.Mesh(new THREE.PlaneGeometry(5.0, 37), pathMat);
    lane.rotation.x = -Math.PI / 2;
    lane.position.y = -0.015;
    lane.receiveShadow = true;
    laneGroup.add(lane);
    for (const side of [-1, 1]) {
      const edge = new THREE.Mesh(new THREE.BoxGeometry(0.16, 0.12, 37), trimMat);
      edge.position.set(side * 2.54, -0.005, 0);
      laneGroup.add(edge);
      const thin = new THREE.Mesh(new THREE.BoxGeometry(0.045, 0.035, 36.7), new THREE.MeshStandardMaterial({ color: 0xb09862, roughness: .7, metalness: .12 }));
      thin.position.set(side * 2.64, 0.055, 0);
      laneGroup.add(thin);
    }
    scene.add(laneGroup);

    const riverGroup = new THREE.Group();
    riverGroup.rotation.y = -Math.PI / 4;
    const river = new THREE.Mesh(new THREE.PlaneGeometry(2.0, 28), waterMat);
    river.rotation.x = -Math.PI / 2;
    river.position.y = 0.005;
    riverGroup.add(river);
    for (const side of [-1, 1]) {
      const bank = new THREE.Mesh(new THREE.BoxGeometry(2.15, 0.1, 0.19), new THREE.MeshStandardMaterial({ color: 0x8c8867, roughness: 1 }));
      bank.position.set(0, 0.025, side * 14.1);
      riverGroup.add(bank);
    }
    scene.add(riverGroup);

    const bridge = new THREE.Group();
    bridge.rotation.y = Math.PI / 4;
    const bridgeDeck = new THREE.Mesh(new THREE.BoxGeometry(5.45, 0.21, 2.7), new THREE.MeshStandardMaterial({ map: stoneMap, color: 0xe0d6b4, roughness: .86 }));
    bridgeDeck.position.y = 0.12;
    bridgeDeck.receiveShadow = true;
    bridgeDeck.castShadow = true;
    bridge.add(bridgeDeck);
    for (let i = -2; i <= 2; i++) {
      const rune = new THREE.Mesh(new THREE.BoxGeometry(.035, .012, 2.24), new THREE.MeshBasicMaterial({ color: i === 0 ? 0x94e3d9 : 0xc4ac73, transparent: true, opacity: .6 }));
      rune.position.set(i * 1.04, .232, 0);
      bridge.add(rune);
    }
    scene.add(bridge);

    // Pale broken-stone rings make the central crossing legible from the MOBA camera.
    const ruinCount = isTouchDevice ? 5 : 8;
    for (let i = 0; i < ruinCount; i++) {
      const angle = i * Math.PI * 2 / ruinCount;
      const pillar = new THREE.Mesh(new THREE.CylinderGeometry(.14, .19, .45 + (i % 3) * .1, 5), stoneMat);
      pillar.position.set(Math.cos(angle) * 4.15, .1, Math.sin(angle) * 4.15);
      pillar.rotation.z = (i % 2 ? .12 : -.1);
      pillar.castShadow = true;
      scene.add(pillar);
      const cap = new THREE.Mesh(new THREE.DodecahedronGeometry(.16, 0), new THREE.MeshStandardMaterial({ color: 0xbba878, roughness: .78, emissive: 0x3b2b11, emissiveIntensity: .15 }));
      cap.position.set(Math.cos(angle) * 4.15, .37 + (i % 3) * .1, Math.sin(angle) * 4.15);
      scene.add(cap);
    }

    const rand = randomSeed(777);
    const treeCount = isTouchDevice ? 8 : 24;
    for (let i = 0; i < treeCount; i++) {
      const t = -11.5 + rand() * 23;
      const offset = (i % 2 ? 1 : -1) * (5.5 + rand() * 5.5);
      const x = t - offset * .707;
      const z = t + offset * .707;
      if (Math.abs(x) > 15 || Math.abs(z) > 15) continue;
      if (Math.hypot(x, z) > 17.4) continue;
      makeTree(x, z, .72 + rand() * .7, rand());
    }
    const rockCount = isTouchDevice ? 12 : 44;
    for (let i = 0; i < rockCount; i++) {
      const x = -14 + rand() * 28;
      const z = -14 + rand() * 28;
      if (Math.abs(x - z) < 3.8 || Math.abs(x + z) < 2.2) continue;
      if (Math.hypot(x, z) > 17) continue;
      const rock = new THREE.Mesh(new THREE.DodecahedronGeometry(.14 + rand() * .18, 0), i % 3 ? stoneMat : darkStoneMat);
      rock.position.set(x, -0.02, z);
      rock.rotation.set(rand() * .4, rand() * Math.PI, rand() * .4);
      rock.scale.y = .45 + rand() * .5;
      rock.castShadow = i % 4 === 0;
      scene.add(rock);
    }
    createArenaDoodads();
  }

  function makeTree(x, z, size, seed) {
    const rand = randomSeed(Math.floor(seed * 100000) + 13);
    const group = new THREE.Group();
    group.position.set(x, 0, z);
    group.rotation.y = rand() * Math.PI * 2;
    const trunk = new THREE.Mesh(new THREE.CylinderGeometry(.11 * size, .16 * size, .78 * size, 6), new THREE.MeshStandardMaterial({ color: 0x594532, roughness: 1 }));
    trunk.position.y = .36 * size;
    trunk.castShadow = true;
    group.add(trunk);
    const colors = [0x315b43, 0x3d6849, 0x426e52, 0x2a5140];
    for (let i = 0; i < 3; i++) {
      const cone = new THREE.Mesh(new THREE.ConeGeometry((.66 - i * .12) * size, (.9 - i * .08) * size, 6), new THREE.MeshStandardMaterial({ color: colors[Math.floor(rand() * colors.length)], roughness: .92 }));
      cone.position.y = (.82 + i * .42) * size;
      cone.rotation.y = rand() * .45;
      cone.castShadow = true;
      group.add(cone);
    }
    const glint = new THREE.Mesh(new THREE.SphereGeometry(.045 * size, 6, 5), new THREE.MeshBasicMaterial({ color: rand() > .5 ? 0x66c3ac : 0xd4bb77, transparent: true, opacity: .72 }));
    glint.position.set((rand() - .5) * .8 * size, 1.15 * size, .27 * size);
    group.add(glint);
    scene.add(group);
  }

  function createArenaDoodads() {
    const rand = randomSeed(4421);
    const flowerCount = isTouchDevice ? 8 : 32;
    for (let i = 0; i < flowerCount; i++) {
      const t = -12 + rand() * 24;
      const side = rand() > .5 ? 1 : -1;
      const offset = side * (3.3 + rand() * 2.2);
      const x = t - offset * .707;
      const z = t + offset * .707;
      const flower = new THREE.Group();
      flower.position.set(x, 0, z);
      const center = new THREE.Mesh(new THREE.SphereGeometry(.07, 6, 5), new THREE.MeshBasicMaterial({ color: i % 3 ? 0xd3bb74 : 0x87d6c2, transparent: true, opacity: .88 }));
      center.position.y = .15;
      flower.add(center);
      for (let j = 0; j < 3; j++) {
        const blade = new THREE.Mesh(new THREE.ConeGeometry(.04, .25 + rand() * .12, 4), new THREE.MeshStandardMaterial({ color: 0x577657, roughness: 1 }));
        blade.position.set((rand() - .5) * .2, .12, (rand() - .5) * .2);
        blade.rotation.z = (rand() - .5) * .5;
        flower.add(blade);
      }
      scene.add(flower);
    }
    const moteGeometry = new THREE.BufferGeometry();
    const positions = [];
    const moteCount = isTouchDevice ? 36 : 105;
    for (let i = 0; i < moteCount; i++) positions.push(-15 + rand() * 30, .5 + rand() * 5, -15 + rand() * 30);
    moteGeometry.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    const motes = new THREE.Points(moteGeometry, new THREE.PointsMaterial({ color: 0xcad39a, size: .065, transparent: true, opacity: .52, sizeAttenuation: true }));
    motes.name = "dust-motes";
    scene.add(motes);
  }

  function addMesh(parent, geometry, material, x, y, z, sx, sy, sz, rx, ry, rz) {
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(x || 0, y || 0, z || 0);
    if (sx !== undefined) mesh.scale.set(sx, sy === undefined ? sx : sy, sz === undefined ? sx : sz);
    if (rx) mesh.rotation.x = rx;
    if (ry) mesh.rotation.y = ry;
    if (rz) mesh.rotation.z = rz;
    parent.add(mesh);
    return mesh;
  }

  function makeBillboardBar(parent, team, y, width) {
    const c = document.createElement("canvas");
    c.width = 128;
    c.height = 16;
    const ctx = c.getContext("2d");
    const texture = new THREE.CanvasTexture(c);
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false, depthWrite: false }));
    sprite.position.set(0, y, 0);
    sprite.scale.set(width || 1.25, .15, 1);
    sprite.renderOrder = 10;
    parent.add(sprite);
    return { canvas: c, ctx, texture, sprite, team };
  }

  function paintBar(bar, health, maxHealth, team) {
    if (!bar) return;
    const ctx = bar.ctx;
    ctx.clearRect(0, 0, 128, 16);
    ctx.fillStyle = "rgba(3,8,9,.82)";
    ctx.fillRect(0, 1, 128, 14);
    ctx.fillStyle = "rgba(228,227,205,.55)";
    ctx.fillRect(0, 1, 128, 1);
    const ratio = clamp(health / maxHealth, 0, 1);
    const color = team === BLUE ? "#63d4d8" : team === "neutral" ? "#d6ad72" : "#f17868";
    ctx.fillStyle = color;
    ctx.fillRect(3, 4, 122 * ratio, 8);
    ctx.fillStyle = "rgba(255,255,255,.24)";
    ctx.fillRect(3, 4, 122 * ratio, 1);
    bar.texture.needsUpdate = true;
  }

  function createBase(team, x, z) {
    const blue = team === BLUE;
    const color = blue ? 0x4eacc3 : 0xd66e64;
    const emissive = blue ? 0x113f52 : 0x501b2b;
    const root = new THREE.Group();
    root.position.set(x, 0, z);
    const stone = new THREE.MeshStandardMaterial({ color: blue ? 0x475e59 : 0x65544c, roughness: .72, metalness: .14 });
    const metal = new THREE.MeshStandardMaterial({ color: blue ? 0x7ea39a : 0xb27d67, roughness: .4, metalness: .56 });
    const crystalMat = new THREE.MeshStandardMaterial({ color, emissive, emissiveIntensity: 1.2, roughness: .22, metalness: .2 });
    addMesh(root, new THREE.CylinderGeometry(2.05, 2.35, .43, 8), stone, 0, .1, 0).castShadow = true;
    addMesh(root, new THREE.CylinderGeometry(1.52, 1.73, .18, 10), metal, 0, .4, 0);
    const ring = addMesh(root, new THREE.TorusGeometry(1.77, .055, 8, 36), new THREE.MeshBasicMaterial({ color: blue ? 0x78e1dd : 0xffa082, transparent: true, opacity: .82 }), 0, .52, 0, 1, 1, 1, -Math.PI / 2);
    ring.rotation.x = -Math.PI / 2;
    const heart = addMesh(root, new THREE.OctahedronGeometry(.84, 0), crystalMat, 0, 1.57, 0, .82, 1.62, .82, 0, .18);
    heart.castShadow = true;
    const halo = addMesh(root, new THREE.TorusGeometry(.96, .045, 7, 30), new THREE.MeshBasicMaterial({ color: blue ? 0x7ee8e3 : 0xffa182, transparent: true, opacity: .64 }), 0, 1.51, 0, 1, 1, 1, .62);
    halo.rotation.x = Math.PI / 2;
    for (let i = 0; i < 5; i++) {
      const a = i * Math.PI * 2 / 5;
      const p = addMesh(root, new THREE.CylinderGeometry(.12, .2, .72, 5), metal, Math.cos(a) * 1.52, .64, Math.sin(a) * 1.52);
      p.rotation.z = -.14 * Math.cos(a);
      addMesh(root, new THREE.SphereGeometry(.12, 6, 5), crystalMat, Math.cos(a) * 1.52, 1.07, Math.sin(a) * 1.52, .7, 1.15, .7);
    }
    const glow = new THREE.PointLight(color, 1.1, 7, 2);
    glow.position.set(0, 1.65, 0);
    root.add(glow);
    root.traverse((obj) => { if (obj.isMesh) { obj.castShadow = obj.castShadow || false; obj.receiveShadow = true; } });
    const bar = makeBillboardBar(root, team, 3.1, 1.6);
    scene.add(root);
    return { type: "core", team, x, z, hp: 1800, maxHp: 1800, alive: true, model: root, bar, cooldown: 0 };
  }

  function createTower(team, x, z) {
    const blue = team === BLUE;
    const color = blue ? 0x55c3dc : 0xed7d6e;
    const stone = new THREE.MeshStandardMaterial({ color: blue ? 0x50635d : 0x69534c, roughness: .7, metalness: .18 });
    const trim = new THREE.MeshStandardMaterial({ color: blue ? 0x92c5bc : 0xd69a7f, roughness: .42, metalness: .4 });
    const gem = new THREE.MeshStandardMaterial({ color, emissive: blue ? 0x0c596b : 0x6d1d28, emissiveIntensity: 1.3, roughness: .2, metalness: .25 });
    const root = new THREE.Group();
    root.position.set(x, 0, z);
    addMesh(root, new THREE.CylinderGeometry(1.05, 1.24, .38, 8), stone, 0, .17, 0).castShadow = true;
    addMesh(root, new THREE.CylinderGeometry(.75, .86, .22, 8), trim, 0, .43, 0);
    addMesh(root, new THREE.CylinderGeometry(.48, .65, 1.25, 7), stone, 0, 1.12, 0).castShadow = true;
    for (let i = 0; i < 4; i++) {
      const a = i * Math.PI / 2 + Math.PI / 4;
      const fin = addMesh(root, new THREE.ConeGeometry(.2, .73, 5), trim, Math.cos(a) * .53, 1.65, Math.sin(a) * .53, .85, 1.2, .85);
      fin.rotation.z = -.17 * Math.cos(a);
    }
    const crystal = addMesh(root, new THREE.OctahedronGeometry(.48, 0), gem, 0, 2.03, 0, .65, 1.3, .65, 0, .2);
    crystal.castShadow = true;
    const ring = addMesh(root, new THREE.TorusGeometry(.6, .035, 7, 24), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .74 }), 0, 1.96, 0);
    ring.rotation.x = Math.PI / 2;
    root.add(new THREE.PointLight(color, .85, 5, 2).translateY(1.7));
    root.traverse((obj) => { if (obj.isMesh) { obj.castShadow = obj.castShadow || false; obj.receiveShadow = true; } });
    const bar = makeBillboardBar(root, team, 2.8, 1.45);
    scene.add(root);
    return { type: "tower", team, x, z, hp: 980, maxHp: 980, alive: true, model: root, bar, cooldown: .5 };
  }

  function createJungleShrine(x, z) {
    const shrine = new THREE.Group();
    shrine.position.set(x, 0, z);
    const stone = new THREE.MeshStandardMaterial({ color: 0x3c3747, roughness: .82, metalness: .17 });
    const trim = new THREE.MeshStandardMaterial({ color: 0x8f789a, roughness: .44, metalness: .48 });
    const rune = new THREE.MeshBasicMaterial({ color: 0xc6a0e5, transparent: true, opacity: .76 });
    addMesh(shrine, new THREE.CylinderGeometry(1.48, 1.7, .22, 9), stone, 0, .02, 0).receiveShadow = true;
    addMesh(shrine, new THREE.CylinderGeometry(1.12, 1.25, .13, 9), trim, 0, .19, 0);
    const ring = addMesh(shrine, new THREE.TorusGeometry(1.28, .035, 6, 36), rune, 0, .27, 0);
    ring.rotation.x = Math.PI / 2;
    for (let i = 0; i < 8; i++) {
      const angle = i * Math.PI / 4;
      const p = addMesh(shrine, new THREE.CylinderGeometry(.11, .18, .45 + (i % 3) * .12, 5), stone, Math.cos(angle) * 1.35, .25, Math.sin(angle) * 1.35, 1, 1, 1, 0, 0, -.18 * Math.cos(angle));
      p.castShadow = !isTouchDevice;
      if (i % 2 === 0) addMesh(shrine, new THREE.OctahedronGeometry(.13, 0), rune, Math.cos(angle) * 1.35, .56 + (i % 3) * .12, Math.sin(angle) * 1.35);
    }
    addMesh(shrine, new THREE.RingGeometry(.38, .48, 28), new THREE.MeshBasicMaterial({ color: 0x9e7db3, transparent: true, opacity: .48, side: THREE.DoubleSide }), 0, .29, 0, 1, 1, 1, -Math.PI / 2);
    scene.add(shrine);
    return shrine;
  }

  function addHeroSilhouette(target, heroId, style) {
    if (!heroId || !style) return;
    const trim = new THREE.MeshStandardMaterial({ color: style.trim, roughness: .36, metalness: .42, emissive: style.glow, emissiveIntensity: .12 });
    const blade = new THREE.MeshStandardMaterial({ color: style.blade, roughness: .24, metalness: .28, emissive: style.glow, emissiveIntensity: .38 });
    const armor = new THREE.MeshStandardMaterial({ color: style.armor, roughness: .4, metalness: .32 });
    const dark = new THREE.MeshStandardMaterial({ color: style.dark, roughness: .72, metalness: .14 });
    if (heroId === "elaris") {
      const halo = addMesh(target, new THREE.TorusGeometry(.34, .03, 6, 20), new THREE.MeshBasicMaterial({ color: style.glow, transparent: true, opacity: .82 }), 0, 2.12, -.05);
      halo.rotation.x = Math.PI / 2;
      for (const side of [-1, 1]) addMesh(target, new THREE.OctahedronGeometry(.11, 0), blade, side * .44, 1.25, -.28, .8, 1.5, .8);
    } else if (heroId === "raen") {
      for (const side of [-1, 1]) {
        const dagger = addMesh(target, new THREE.BoxGeometry(.075, .62, .045), blade, side * .61, 1.38, .18, 1, 1, 1, 0, 0, side * .27);
        dagger.castShadow = true;
        addMesh(target, new THREE.ConeGeometry(.12, .2, 4), trim, side * .61, 1.76, .18, 1, 1, 1, 0, 0, Math.PI);
      }
      addMesh(target, new THREE.ConeGeometry(.28, .7, 5), dark, 0, .9, -.46, 1.15, 1, .5, -.18);
    } else if (heroId === "varkor") {
      for (const side of [-1, 1]) addMesh(target, new THREE.DodecahedronGeometry(.29, 0), armor, side * .49, 1.37, -.01, 1.25, .83, 1.1);
      addMesh(target, new THREE.BoxGeometry(.34, .52, .16), dark, -.57, 1.08, .25, 1, 1, 1, 0, 0, -.1);
      addMesh(target, new THREE.BoxGeometry(.17, .68, .13), blade, .62, 1.48, .19, 1, 1, 1, 0, 0, -.12);
      addMesh(target, new THREE.BoxGeometry(.44, .18, .23), trim, .68, 1.82, .19, 1, 1, 1, 0, 0, -.12);
    }
  }

  function createCharacterModel(team, kind, compact, heroId, skinId) {
    const isHero = kind === "hero";
    const blue = team === BLUE;
    const root = new THREE.Group();
    const visual = new THREE.Group();
    root.add(visual);
    const style = isHero && blue && window.RiftMeta ? window.RiftMeta.getSkinPalette(heroId || selectedHeroId, skinId || selectedSkinId) : null;
    if (kind === "monster") {
      const disk = new THREE.Mesh(new THREE.CircleGeometry(.76, 22), new THREE.MeshBasicMaterial({ color: 0x17101c, transparent: true, opacity: .46, depthWrite: false }));
      disk.rotation.x = -Math.PI / 2; disk.position.y = .03; root.add(disk);
      const ring = new THREE.Mesh(new THREE.TorusGeometry(.71, .055, 7, 24), new THREE.MeshBasicMaterial({ color: 0xd5a35d, transparent: true, opacity: .9 }));
      ring.rotation.x = -Math.PI / 2; ring.position.y = .06; root.add(ring);
      const hide = new THREE.MeshStandardMaterial({ color: 0x302b43, roughness: .8, metalness: .12 });
      const shell = new THREE.MeshStandardMaterial({ color: 0x775d87, roughness: .48, metalness: .3, emissive: 0x2f183d, emissiveIntensity: .34 });
      const crystal = new THREE.MeshStandardMaterial({ color: 0xc8a5ed, roughness: .22, metalness: .2, emissive: 0x7c3ea8, emissiveIntensity: .82 });
      addMesh(visual, new THREE.IcosahedronGeometry(.72, 1), hide, 0, .88, 0, 1.0, .82, .9).castShadow = true;
      addMesh(visual, new THREE.IcosahedronGeometry(.49, 0), shell, 0, 1.24, .02, 1.1, 1.03, .92).castShadow = true;
      for (const side of [-1, 1]) {
        addMesh(visual, new THREE.ConeGeometry(.18, .55, 5), crystal, side * .36, 1.71, -.04, 1, 1, 1, 0, 0, side * -.25);
        addMesh(visual, new THREE.CylinderGeometry(.14, .22, .63, 6), hide, side * .7, .85, 0, 1, 1, 1, 0, 0, side * -.2);
      }
      addMesh(visual, new THREE.OctahedronGeometry(.22, 0), crystal, 0, 1.12, .57, 1, 1.2, .75);
      root.userData.visual = visual; root.userData.kind = kind; root.userData.team = team;
      return root;
    }
    if (!isHero) {
      const primary = new THREE.MeshStandardMaterial({ color: blue ? 0x276a77 : 0x8b3945, roughness: .55, metalness: .25 });
      const armor = new THREE.MeshStandardMaterial({ color: blue ? 0x4fa8ad : 0xc9685f, roughness: .42, metalness: .42 });
      const trim = new THREE.MeshStandardMaterial({ color: blue ? 0xd6b878 : 0xefb875, roughness: .35, metalness: .55 });
      const dark = new THREE.MeshStandardMaterial({ color: blue ? 0x182e32 : 0x342027, roughness: .72 });
      const blade = new THREE.MeshStandardMaterial({ color: blue ? 0xcdf7e9 : 0xffc2a0, roughness: .25, metalness: .38, emissive: blue ? 0x55d6cb : 0xea6b63, emissiveIntensity: .42 });
      const disk = new THREE.Mesh(new THREE.CircleGeometry(.43, 20), new THREE.MeshBasicMaterial({ color: 0x07100d, transparent: true, opacity: .4, depthWrite: false }));
      disk.rotation.x = -Math.PI / 2;
      disk.position.y = .028;
      root.add(disk);
      const ring = new THREE.Mesh(new THREE.TorusGeometry(.39, .03, 6, 20), new THREE.MeshBasicMaterial({ color: blue ? 0x63d7e2 : 0xf07d70, transparent: true, opacity: .78 }));
      ring.rotation.x = -Math.PI / 2;
      ring.position.y = .055;
      root.add(ring);
      const scaledMinion = new THREE.Group();
      scaledMinion.scale.setScalar(.63);
      visual.add(scaledMinion);
      addMesh(scaledMinion, new THREE.CylinderGeometry(.23, .34, .66, 6), primary, 0, .75, 0).castShadow = !isTouchDevice;
      addMesh(scaledMinion, new THREE.SphereGeometry(.19, 7, 6), armor, 0, 1.23, .03, 1, 1.04, .95);
      addMesh(scaledMinion, new THREE.ConeGeometry(.24, .34, 5), trim, 0, 1.4, -.035);
      addMesh(scaledMinion, new THREE.BoxGeometry(.08, .84, .08), blade, .35, .88, .12, 1, 1, 1, 0, 0, -.16);
      root.traverse((obj) => { if (obj.isMesh) { obj.castShadow = obj.castShadow || !isTouchDevice; obj.receiveShadow = false; } });
      root.userData.visual = visual;
      root.userData.kind = kind;
      root.userData.team = team;
      return root;
    }
    if (compact) {
      const primary = new THREE.MeshStandardMaterial({ color: blue ? 0x276a77 : 0x8b3945, roughness: .48, metalness: .3, emissive: blue ? 0x09232d : 0x2b0912, emissiveIntensity: .28 });
      const armor = new THREE.MeshStandardMaterial({ color: blue ? 0x4fa8ad : 0xc9685f, roughness: .38, metalness: .48 });
      const trim = new THREE.MeshStandardMaterial({ color: blue ? 0xd6b878 : 0xefb875, roughness: .32, metalness: .58 });
      const dark = new THREE.MeshStandardMaterial({ color: blue ? 0x182e32 : 0x342027, roughness: .65 });
      const skin = new THREE.MeshStandardMaterial({ color: blue ? 0xd5b18c : 0xc7907d, roughness: .82 });
      const blade = new THREE.MeshStandardMaterial({ color: blue ? 0xcdf7e9 : 0xffc2a0, roughness: .22, metalness: .4, emissive: blue ? 0x55d6cb : 0xea6b63, emissiveIntensity: .55 });
      if (style) { primary.color.setHex(style.primary); armor.color.setHex(style.armor); trim.color.setHex(style.trim); dark.color.setHex(style.dark); blade.color.setHex(style.blade); primary.emissive.setHex(style.primary); blade.emissive.setHex(style.glow); }
      const disk = new THREE.Mesh(new THREE.CircleGeometry(.67, 20), new THREE.MeshBasicMaterial({ color: 0x07100d, transparent: true, opacity: .43, depthWrite: false }));
      disk.rotation.x = -Math.PI / 2;
      disk.position.y = .028;
      root.add(disk);
      const ring = new THREE.Mesh(new THREE.TorusGeometry(.62, .045, 6, 22), new THREE.MeshBasicMaterial({ color: blue ? 0x63d7e2 : 0xf07d70, transparent: true, opacity: .86 }));
      ring.rotation.x = -Math.PI / 2;
      ring.position.y = .055;
      root.add(ring);
      const compactVisual = new THREE.Group();
      visual.add(compactVisual);
      addMesh(compactVisual, new THREE.ConeGeometry(.43, .92, 5), blue ? trim : dark, 0, .82, -.27, .9, 1, .62, -.16).castShadow = !isTouchDevice;
      addMesh(compactVisual, new THREE.CylinderGeometry(.27, .36, .69, 6), primary, 0, 1.02, 0).castShadow = !isTouchDevice;
      addMesh(compactVisual, new THREE.SphereGeometry(.36, 7, 6), armor, 0, 1.19, .04, 1, .82, .8);
      for (const side of [-1, 1]) addMesh(compactVisual, new THREE.CylinderGeometry(.1, .11, .6, 5), primary, side * .43, 1.03, .02, 1, 1, 1, 0, 0, side * -.23);
      addMesh(compactVisual, new THREE.SphereGeometry(.235, 8, 6), skin, 0, 1.75, .02, 1, 1.06, .95);
      addMesh(compactVisual, new THREE.SphereGeometry(.255, 7, 5, 0, Math.PI * 2, 0, Math.PI * .58), armor, 0, 1.84, -.02, 1.04, .9, 1);
      addMesh(compactVisual, new THREE.ConeGeometry(.1, .34, 5), trim, -.16, 2.03, -.02, 1, 1, 1, 0, 0, .14);
      addMesh(compactVisual, new THREE.ConeGeometry(.1, .34, 5), trim, .16, 2.03, -.02, 1, 1, 1, 0, 0, -.14);
      addMesh(compactVisual, new THREE.BoxGeometry(.12, .84, .07), blade, .57, 1.48, .2, 1, 1, 1, 0, 0, -.3).castShadow = !isTouchDevice;
      addMesh(compactVisual, new THREE.BoxGeometry(.32, .08, .12), trim, .56, 1.13, .2, 1, 1, 1, 0, 0, -.3);
      addMesh(compactVisual, new THREE.CylinderGeometry(.04, .04, .2, 5), dark, .51, .9, .2, 1, 1, 1, 0, 0, -.3);
      addMesh(compactVisual, new THREE.SphereGeometry(.11, 7, 5), blade, 0, 1.18, -.25);
      addHeroSilhouette(compactVisual, heroId || selectedHeroId, style);
      root.traverse((obj) => { if (obj.isMesh) { obj.castShadow = obj.castShadow || !isTouchDevice; obj.receiveShadow = false; } });
      root.userData.visual = visual;
      root.userData.kind = kind;
      root.userData.team = team;
      return root;
    }
    const size = 1;
    const primary = new THREE.MeshStandardMaterial({ color: blue ? 0x276a77 : 0x8b3945, roughness: .45, metalness: .32, emissive: blue ? 0x09232d : 0x2b0912, emissiveIntensity: .35 });
    const armor = new THREE.MeshStandardMaterial({ color: blue ? 0x4fa8ad : 0xc9685f, roughness: .37, metalness: .54 });
    const trim = new THREE.MeshStandardMaterial({ color: blue ? 0xd6b878 : 0xefb875, roughness: .32, metalness: .67, emissive: blue ? 0x2f1f09 : 0x36150a, emissiveIntensity: .2 });
    const dark = new THREE.MeshStandardMaterial({ color: blue ? 0x182e32 : 0x342027, roughness: .65, metalness: .18 });
    const skin = new THREE.MeshStandardMaterial({ color: blue ? 0xd5b18c : 0xc7907d, roughness: .82 });
    const blade = new THREE.MeshStandardMaterial({ color: blue ? 0xcdf7e9 : 0xffc2a0, roughness: .22, metalness: .43, emissive: blue ? 0x55d6cb : 0xea6b63, emissiveIntensity: .65 });
    if (style) { primary.color.setHex(style.primary); armor.color.setHex(style.armor); trim.color.setHex(style.trim); dark.color.setHex(style.dark); blade.color.setHex(style.blade); primary.emissive.setHex(style.primary); blade.emissive.setHex(style.glow); }
    const footMat = dark;

    const disk = new THREE.Mesh(new THREE.CircleGeometry(isHero ? .67 : .43, 28), new THREE.MeshBasicMaterial({ color: 0x07100d, transparent: true, opacity: .43, depthWrite: false }));
    disk.rotation.x = -Math.PI / 2;
    disk.position.y = .028;
    root.add(disk);
    const ring = new THREE.Mesh(new THREE.TorusGeometry(isHero ? .62 : .39, isHero ? .045 : .03, 7, 28), new THREE.MeshBasicMaterial({ color: blue ? 0x63d7e2 : 0xf07d70, transparent: true, opacity: .86 }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = .055;
    root.add(ring);

    const scaled = new THREE.Group();
    scaled.scale.setScalar(size);
    visual.add(scaled);
    addMesh(scaled, new THREE.CylinderGeometry(.22, .3, .2, 6), footMat, -.19, .19, .04).castShadow = true;
    addMesh(scaled, new THREE.CylinderGeometry(.22, .3, .2, 6), footMat, .19, .19, .04).castShadow = true;
    addMesh(scaled, new THREE.ConeGeometry(.43, .73, 7), primary, 0, .58, -.12, 1, 1, 1, Math.PI, 0, 0).castShadow = true;
    const body = addMesh(scaled, new THREE.CylinderGeometry(.27, .36, .67, 7), primary, 0, 1.01, 0);
    body.castShadow = true;
    const breast = addMesh(scaled, new THREE.SphereGeometry(.36, 9, 7), armor, 0, 1.17, .035, 1, .82, .8);
    breast.castShadow = true;
    addMesh(scaled, new THREE.TorusGeometry(.31, .026, 6, 16), trim, 0, .83, 0, 1, 1, 1, Math.PI / 2);
    const cape = addMesh(scaled, new THREE.ConeGeometry(.42, .88, 5), blue ? trim : dark, 0, .83, -.33, .95, 1, .62, -.18, 0, 0);
    cape.castShadow = true;
    for (const side of [-1, 1]) {
      const shoulder = addMesh(scaled, new THREE.SphereGeometry(.2, 7, 6), trim, side * .4, 1.39, 0, 1.12, .8, .9);
      shoulder.castShadow = true;
      const arm = addMesh(scaled, new THREE.CylinderGeometry(.105, .11, .62, 6), primary, side * .46, 1.03, .02, 1, 1, 1, 0, 0, side * -.24);
      arm.castShadow = true;
      addMesh(scaled, new THREE.SphereGeometry(.11, 6, 5), skin, side * .53, .71, .12, .9, 1, .9);
    }
    addMesh(scaled, new THREE.SphereGeometry(.245, 10, 8), skin, 0, 1.72, .02, 1, 1.08, .95).castShadow = true;
    const helm = addMesh(scaled, new THREE.SphereGeometry(.265, 8, 6, 0, Math.PI * 2, 0, Math.PI * .58), blue ? armor : primary, 0, 1.82, -.018, 1.04, .88, 1);
    helm.castShadow = true;
    addMesh(scaled, new THREE.BoxGeometry(.31, .055, .18), trim, 0, 1.72, .226);
    addMesh(scaled, new THREE.SphereGeometry(.038, 6, 5), blade, 0, 1.73, .32, 1, 1, .65);
    for (const side of [-1, 1]) {
      const horn = addMesh(scaled, new THREE.ConeGeometry(isHero ? .09 : .06, isHero ? .34 : .21, 5), trim, side * .18, 2.04, -.025, 1, 1, 1, 0, 0, side * -.18);
      horn.castShadow = true;
    }
    const swordGuard = addMesh(scaled, new THREE.BoxGeometry(.32, .08, .12), trim, .56, 1.13, .21, 1, 1, 1, 0, 0, -.36);
    swordGuard.castShadow = true;
    const sword = addMesh(scaled, new THREE.BoxGeometry(.12, isHero ? .88 : .6, .055), blade, .57, 1.48, .21, 1, 1, 1, 0, 0, -.3);
    sword.castShadow = true;
    addMesh(scaled, new THREE.CylinderGeometry(.045, .045, .22, 6), dark, .51, .91, .21, 1, 1, 1, 0, 0, -.3);
    const glow = new THREE.Mesh(new THREE.SphereGeometry(.12, 8, 6), new THREE.MeshBasicMaterial({ color: blue ? 0x72eee3 : 0xff8a7b, transparent: true, opacity: .8 }));
    glow.position.set(0, 1.18, -.26);
    scaled.add(glow);
    if (style) glow.material.color.setHex(style.glow);
    addHeroSilhouette(scaled, heroId || selectedHeroId, style);

    root.traverse((obj) => {
      if (obj.isMesh) {
        obj.castShadow = isHero || obj.castShadow;
        obj.receiveShadow = true;
      }
    });
    root.userData.visual = visual;
    root.userData.kind = kind;
    root.userData.team = team;
    return root;
  }

  function createArena() {
    makeGround();
    createJungleShrine(1.8, -4.5);
    // A faint island beneath the selected champion keeps the lobby portrait separate from the lane.
    menuPlatform = new THREE.Group();
    menuPlatform.position.set(2.45, 0, -0.2);
    const daisStone = new THREE.MeshStandardMaterial({ color: 0x3e4d47, roughness: .7, metalness: .26 });
    const daisGold = new THREE.MeshBasicMaterial({ color: 0xc5a364, transparent: true, opacity: .68 });
    addMesh(menuPlatform, new THREE.CylinderGeometry(1.72, 1.95, .24, 8), daisStone, 0, .08, 0).receiveShadow = true;
    const daisRing = addMesh(menuPlatform, new THREE.TorusGeometry(1.54, .034, 7, 36), daisGold, 0, .22, 0);
    daisRing.rotation.x = -Math.PI / 2;
    for (let i = 0; i < 6; i++) {
      const a = i * Math.PI / 3;
      addMesh(menuPlatform, new THREE.BoxGeometry(.23, .04, .09), daisGold, Math.cos(a) * 1.12, .225, Math.sin(a) * 1.12, 1, 1, 1, 0, -a);
    }
    scene.add(menuPlatform);
    menuHero = createCharacterModel(BLUE, "hero", false, selectedHeroId, selectedSkinId);
    menuHero.position.set(2.45, .22, -.2);
    menuHero.rotation.y = -.45;
    scene.add(menuHero);

    structures = [
      createBase(BLUE, -12, -12),
      createTower(BLUE, -5.4, -5.4),
      createTower(RED, 5.4, 5.4),
      createBase(RED, 12, 12)
    ];
    cameraTarget.set(-1.2, 0, -.25);
    showcaseLight = new THREE.PointLight(0x6dd6c8, 1.2, 8, 2);
    showcaseLight.position.set(2.45, 2.4, -.2);
    scene.add(showcaseLight);
  }

  createArena();

  function setShowcaseHero(heroId, skinId) {
    if (!HEROES[heroId]) heroId = "elaris";
    selectedHeroId = heroId;
    selectedSkinId = skinId || HEROES[heroId].skins[0].id;
    activeAbilities = HEROES[heroId].abilities;
    if (menuHero) {
      scene.remove(menuHero);
      clearTree(menuHero);
    }
    menuHero = createCharacterModel(BLUE, "hero", false, selectedHeroId, selectedSkinId);
    menuHero.position.set(2.45, .22, -.2);
    menuHero.rotation.y = -.45;
    menuHero.visible = state.phase === "lobby";
    scene.add(menuHero);
    document.querySelectorAll("[data-ability]").forEach((button) => {
      const ability = activeAbilities[button.dataset.ability];
      if (ability) {
        button.setAttribute("aria-label", `${HEROES[heroId].name}: ${ability.name}`);
        button.title = ability.name;
      }
    });
  }
  window.setShowcaseHero = setShowcaseHero;
  setShowcaseHero(selectedHeroId, selectedSkinId);

  function resize() {
    const width = Math.max(1, window.innerWidth);
    const height = Math.max(1, window.innerHeight);
    const heightView = state.phase === "lobby" ? 11.5 : 20.5;
    const aspect = width / height;
    camera.left = -heightView * aspect / 2;
    camera.right = heightView * aspect / 2;
    camera.top = heightView / 2;
    camera.bottom = -heightView / 2;
    camera.updateProjectionMatrix();
    renderer.setSize(width, height, false);
  }
  function applyGraphicsSettings() {
    const requested = window.RiftMeta ? window.RiftMeta.settings.quality : "auto";
    const quality = requested === "auto" ? (isTouchDevice ? "low" : "medium") : requested;
    const profile = quality === "high" ? { ratio: 1.8, shadows: true } : quality === "medium" ? { ratio: 1.45, shadows: !isTouchDevice } : { ratio: isTouchDevice ? 1.0 : 1.2, shadows: false };
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, profile.ratio));
    renderer.shadowMap.enabled = profile.shadows;
    sun.castShadow = profile.shadows;
    renderer.setSize(Math.max(1, window.innerWidth), Math.max(1, window.innerHeight), false);
  }
  function playAmbientNote() {
    if (state.phase !== "playing" || !window.RiftMeta || window.RiftMeta.settings.music <= 0) return;
    const context = window.RiftMeta.ensureAudio();
    if (!context) return;
    if (!ambienceMaster) { ambienceMaster = context.createGain(); ambienceMaster.connect(context.destination); }
    const volume = window.RiftMeta.settings.music;
    ambienceMaster.gain.setTargetAtTime(volume * .16, context.currentTime, .35);
    const notes = [110, 146.83, 164.81, 130.81, 98, 146.83, 174.61, 130.81];
    const oscillator = context.createOscillator();
    const envelope = context.createGain();
    oscillator.type = "sine";
    oscillator.frequency.setValueAtTime(notes[ambienceStep++ % notes.length], context.currentTime);
    envelope.gain.setValueAtTime(.0001, context.currentTime);
    envelope.gain.exponentialRampToValueAtTime(.2, context.currentTime + .45);
    envelope.gain.exponentialRampToValueAtTime(.0001, context.currentTime + 2.2);
    oscillator.connect(envelope).connect(ambienceMaster);
    oscillator.start();
    oscillator.stop(context.currentTime + 2.25);
  }
  function updateAmbientMusic() {
    if (!window.RiftMeta) return;
    const context = window.RiftMeta.ensureAudio();
    if (!context) return;
    if (!ambienceMaster) { ambienceMaster = context.createGain(); ambienceMaster.connect(context.destination); }
    ambienceMaster.gain.setTargetAtTime(window.RiftMeta.settings.music * .16, context.currentTime, .25);
    if (!ambienceTimer) ambienceTimer = window.setInterval(playAmbientNote, 2400);
  }
  window.addEventListener("resize", resize);
  document.addEventListener("rift-settings-change", () => { applyGraphicsSettings(); updateAmbientMusic(); });
  resize();
  applyGraphicsSettings();

  function actorBarHeight(kind) { return kind === "hero" ? 2.55 : kind === "monster" ? 2.35 : 1.9; }

  function makeActor(team, kind, x, z, isPlayer, heroId) {
    const isHero = kind === "hero";
    const actorHeroId = isHero ? (heroId || (isPlayer ? selectedHeroId : "varkor")) : null;
    const definition = isHero ? defaultHero(actorHeroId) : null;
    const model = createCharacterModel(team, kind, isTouchDevice, actorHeroId, isPlayer ? selectedSkinId : null);
    model.position.set(x, 0, z);
    const hp = kind === "monster" ? 440 : isHero ? definition.stats.hp : 105;
    const actor = {
      team, kind, x, z, hp, maxHp: hp,
      mana: isPlayer ? definition.stats.mana : 0, maxMana: isPlayer ? definition.stats.mana : 0,
      speed: kind === "monster" ? 1.65 : isHero ? definition.stats.speed : 1.65,
      attackRange: kind === "monster" ? 2.2 : isHero ? definition.stats.range : 2.2,
      attackDamage: kind === "monster" ? 34 : isHero ? definition.stats.attack : 18,
      attackDelay: kind === "monster" ? 1.35 : isHero ? definition.stats.delay : 1.32,
      attackCooldown: .35 + Math.random() * .35,
      alive: true, isPlayer: !!isPlayer, model, heroId: actorHeroId,
      level: isPlayer ? 1 : 1, xp: 0, nextLevelXp: 300,
      progress: x, pathDir: team === BLUE ? 1 : -1,
      respawnAt: 0, removeAt: 0, facing: team === BLUE ? Math.PI / 4 : -3 * Math.PI / 4,
      spawnX: x, spawnZ: z, monsterCooldown: .65,
      moved: false, lastTarget: null, visual: model.userData.visual
    };
    actor.bar = makeBillboardBar(model, team, actorBarHeight(kind), isHero ? 1.15 : kind === "monster" ? 1.2 : .72);
    paintBar(actor.bar, actor.hp, actor.maxHp, team);
    scene.add(model);
    actors.push(actor);
    return actor;
  }

  function addCombatText(message, type) {
    const line = document.createElement("div");
    line.className = "feed-line";
    if (type === "good") line.style.color = "#b7e8c7";
    if (type === "danger") line.style.color = "#f4a093";
    line.textContent = message;
    feed.prepend(line);
    while (feed.children.length > 4) feed.lastElementChild.remove();
    window.setTimeout(() => line.remove(), 6200);
  }

  function showObjective(message, duration) {
    const toast = $("objective-toast");
    toast.innerHTML = `<span>✦</span> ${message}`;
    toast.classList.add("show");
    clearTimeout(showObjective.timer);
    showObjective.timer = setTimeout(() => toast.classList.remove("show"), duration || 2800);
  }

  function fmtTime(totalSeconds) {
    const seconds = Math.floor(Math.max(0, totalSeconds));
    const minutes = Math.floor(seconds / 60);
    const remainder = seconds % 60;
    return `${minutes < 10 ? "0" : ""}${minutes}:${remainder < 10 ? "0" : ""}${remainder}`;
  }

  function toggleButton(button, on) {
    if (!button) return;
    button.classList.toggle("pressed", !!on);
  }

  function startMatch() {
    if (state.phase === "playing" || state.phase === "loading") return;
    state.phase = "loading";
    if (document.fullscreenEnabled && !document.fullscreenElement && document.documentElement.requestFullscreen) {
      try { document.documentElement.requestFullscreen().catch(() => {}); } catch (_) { /* iframe previews can deny fullscreen */ }
    }
    if (window.RiftMeta) {
      window.RiftMeta.ensureAudio();
      window.RiftMeta.playUiTone(520, .22, "triangle");
      updateAmbientMusic();
    }
    const overlay = $("loading-overlay");
    const progress = $("loading-progress");
    const percent = $("loading-percent");
    const status = $("loading-status");
    overlay.hidden = false;
    const steps = [
      [16, "Сверяем профиль и выбранного героя…"],
      [36, "Подготавливаем симметричную 3D-карту…"],
      [59, "Расставляем башни и кристаллы команд…"],
      [81, "Проверяем умения и баланс героя…"],
      [100, "Арена готова. Удачи в бою!"]
    ];
    let index = 0;
    const advance = () => {
      const step = steps[index++];
      progress.style.width = `${step[0]}%`;
      percent.textContent = `${step[0]}%`;
      status.textContent = step[1];
      if (index < steps.length) window.setTimeout(advance, 210);
      else window.setTimeout(beginMatch, 300);
    };
    window.setTimeout(advance, 130);
  }

  function beginMatch() {
    clearDynamicEntities();
    menuHero.visible = false;
    menuPlatform.visible = false;
    showcaseLight.visible = false;
    const lines = defaultHero(selectedHeroId).lines || {};
    state = {
      phase: "playing", elapsed: 0, blueScore: 0, redScore: 0, gold: 245,
      kills: 0, deaths: 0, assists: 0, potionUsed: false, xp: 0,
      cooldowns: createCooldownMap()
    };
    structures.forEach((structure) => {
      structure.alive = true;
      structure.hp = structure.maxHp;
      structure.cooldown = structure.type === "tower" ? .8 : 0;
      structure.model.visible = true;
      paintBar(structure.bar, structure.hp, structure.maxHp, structure.team);
    });
    const enemyHeroId = selectedHeroId === "elaris" ? "varkor" : selectedHeroId === "raen" ? "elaris" : "raen";
    player = makeActor(BLUE, "hero", -4.1, -4.1, true, selectedHeroId);
    player.facing = Math.PI / 4;
    enemyHero = makeActor(RED, "hero", 3.4, 3.4, false, enemyHeroId);
    enemyHero.facing = -3 * Math.PI / 4;
    makeActor("neutral", "monster", 1.8, -4.5, false);
    for (let i = 0; i < 3; i++) {
      makeActor(BLUE, "minion", -9.2 - i * .75, -9.2 - i * .75, false);
      makeActor(RED, "minion", 9.2 + i * .75, 9.2 + i * .75, false);
    }
    waveNumber = 1;
    lastWave = 0;
    nextCleanup = 0;
    worldTime = 0;
    joystickInput = { x: 0, z: 0 };
    moveTarget = null;
    attackHeld = false;
    feed.innerHTML = "";
    $("pause-overlay").hidden = true;
    $("result-overlay").hidden = true;
    $("loading-overlay").hidden = true;
    $("respawn-banner").hidden = true;
    $("potion-btn").classList.remove("used");
    $("jungle-buff").hidden = true;
    $("hero-name-hud").textContent = defaultHero(selectedHeroId).name.toLocaleUpperCase("ru-RU");
    $("hero-portrait-letter").textContent = defaultHero(selectedHeroId).symbol;
    $("hero-role-hud").textContent = defaultHero(selectedHeroId).role;
    $("level-badge").textContent = "1";
    lobby.style.display = "none";
    hud.classList.add("active");
    cameraTarget.set(player.x, 0, player.z);
    camera.position.set(player.x, 20.5 * 1.38, player.z + 20.5 * 1.22);
    camera.lookAt(player.x, 0, player.z);
    resize();
    renderHud();
    showObjective("МИНЬОНЫ ВЫХОДЯТ НА ЛИНИЮ · ЗАХВАТИ НЕЙТРАЛЬНЫЙ КРИСТАЛЛ", 3800);
    addCombatText(`Матч начался. ${defaultHero(selectedHeroId).name}, линия за тобой!`, "good");
    if (window.RiftMeta) window.RiftMeta.speak(lines.start, selectedHeroId);
  }

  function clearTree(object) {
    object.traverse((node) => {
      if (node.geometry && node.geometry.dispose) node.geometry.dispose();
      if (node.material) {
        const mats = Array.isArray(node.material) ? node.material : [node.material];
        mats.forEach((mat) => {
          if (mat.map && mat.map.dispose) mat.map.dispose();
          mat.dispose();
        });
      }
    });
  }

  function clearDynamicEntities() {
    for (const actor of actors) {
      scene.remove(actor.model);
      clearTree(actor.model);
    }
    actors = [];
    for (const projectile of projectiles) {
      scene.remove(projectile.mesh);
      clearTree(projectile.mesh);
    }
    projectiles = [];
    for (const effect of effects) {
      scene.remove(effect.object);
      clearTree(effect.object);
    }
    effects = [];
    player = null;
    enemyHero = null;
  }

  function returnToLobby() {
    state.phase = "lobby";
    clearDynamicEntities();
    joystickInput = { x: 0, z: 0 };
    attackHeld = false;
    moveTarget = null;
    $("pause-overlay").hidden = true;
    $("result-overlay").hidden = true;
    $("respawn-banner").hidden = true;
    $("jungle-buff").hidden = true;
    hud.classList.remove("active");
    lobby.style.display = "flex";
    menuHero.visible = true;
    menuPlatform.visible = true;
    showcaseLight.visible = true;
    cameraTarget.set(-1.2, 0, -.25);
    camera.position.set(-1.2, 11.5 * 1.38, -.25 + 11.5 * 1.22);
    camera.lookAt(cameraTarget);
    resize();
  }

  function pauseMatch() {
    if (state.phase !== "playing") return;
    state.phase = "paused";
    attackHeld = false;
    toggleButton($("attack-btn"), false);
    $("pause-overlay").hidden = false;
  }

  function resumeMatch() {
    if (state.phase !== "paused") return;
    $("pause-overlay").hidden = true;
    state.phase = "playing";
    clock.getDelta();
  }

  function finishMatch(victory) {
    if (state.phase !== "playing") return;
    state.phase = "result";
    attackHeld = false;
    toggleButton($("attack-btn"), false);
    $("respawn-banner").hidden = true;
    $("result-title").textContent = victory ? "ПОБЕДА" : "ПОРАЖЕНИЕ";
    $("result-title").style.color = victory ? "#f1d59b" : "#f19b8c";
    $("result-kicker").textContent = victory ? "ВРАЖЕСКИЙ РАЗЛОМ ПАЛ" : "ЯДРО СОЮЗНИКОВ РАЗРУШЕНО";
    $("result-copy").textContent = victory ? "Ты защитила линию и разрушила вражеское ядро." : "Соберись, странник. Каждый бой учит новому.";
    $("result-kda").textContent = `${state.kills} / ${state.deaths} / ${state.assists}`;
    $("result-gold").textContent = state.gold.toLocaleString("ru-RU");
    $("result-time").textContent = fmtTime(state.elapsed);
    $("result-overlay").hidden = false;
    if (window.RiftMeta) {
      window.RiftMeta.recordMatch({ victory, kills: state.kills, deaths: state.deaths, assists: state.assists, duration: state.elapsed, gold: state.gold });
      const lines = defaultHero(selectedHeroId).lines || {};
      window.RiftMeta.speak(victory ? lines.win : "Мы вернёмся сильнее.", selectedHeroId);
      window.RiftMeta.playUiTone(victory ? 730 : 240, .45, "sine");
    }
  }

  function getOpponentTeam(team) { return team === BLUE ? RED : BLUE; }

  function enemyStructureFor(team) {
    const enemyTeam = getOpponentTeam(team);
    const tower = structures.find((s) => s.team === enemyTeam && s.type === "tower");
    if (tower && tower.alive) return tower;
    return structures.find((s) => s.team === enemyTeam && s.type === "core") || null;
  }

  function updateBar(target) {
    paintBar(target.bar, target.hp, target.maxHp, target.team);
  }

  function gainExperience(amount) {
    if (!player || !Number.isFinite(amount) || amount <= 0) return;
    player.xp += amount;
    let leveled = false;
    while (player.level < 12 && player.xp >= player.nextLevelXp) {
      player.xp -= player.nextLevelXp;
      player.level += 1;
      player.nextLevelXp = Math.round(player.nextLevelXp * 1.16);
      const growth = player.heroId === "varkor" ? 105 : 70;
      player.maxHp += growth;
      player.hp = Math.min(player.maxHp, player.hp + growth);
      player.maxMana += 35;
      player.mana = Math.min(player.maxMana, player.mana + 35);
      player.attackDamage += player.heroId === "raen" ? 5 : 4;
      leveled = true;
    }
    if (leveled) {
      updateBar(player);
      $("level-badge").textContent = String(player.level);
      showObjective(`УРОВЕНЬ ${player.level} · ГЕРОЙ СТАЛ СИЛЬНЕЕ`, 2400);
      if (window.RiftMeta) window.RiftMeta.playUiTone(760, .28, "sine");
    }
    const xpBar = $("hero-xp-bar");
    if (xpBar) xpBar.style.width = `${clamp(player.xp / player.nextLevelXp, 0, 1) * 100}%`;
  }

  function dealDamage(target, amount, source) {
    if (!target || !target.alive || state.phase !== "playing") return;
    target.hp = Math.max(0, target.hp - amount);
    updateBar(target);
    if (target.isPlayer) {
      if (source && source.team !== target.team) addCombatText(`Тебя атакует ${source.team === RED ? "вражеский герой" : "отряд"}`, "danger");
    }
    const color = target.team === BLUE ? 0x68d7df : target.team === "neutral" ? 0xd6ad72 : 0xf1786b;
    spawnHit(target.x, target.z, color, target.kind === "hero" || target.kind === "monster" || target.type === "tower" || target.type === "core");
    if (target.hp > 0) return;

    target.alive = false;
    target.model.visible = false;
    target.removeAt = state.elapsed + 4.5;
    if (target.type === "tower") {
      addCombatText(`${target.team === BLUE ? "Союзная" : "Вражеская"} башня разрушена!`, target.team === RED ? "good" : "danger");
      if (target.team === RED) {
        state.gold += 160;
        if (source && source.isPlayer) gainExperience(110);
        showObjective("ВРАЖЕСКАЯ БАШНЯ ПАЛА · ПУТЬ К ЯДРУ ОТКРЫТ", 3600);
      }
      return;
    }
    if (target.type === "core") {
      addCombatText(target.team === RED ? "ЯДРО ПРОТИВНИКА РАЗРУШЕНО!" : "НАШЕ ЯДРО РАЗРУШЕНО!", target.team === RED ? "good" : "danger");
      finishMatch(target.team === RED);
      return;
    }
    if (target.kind === "monster") {
      target.respawnAt = state.elapsed + 42;
      target.removeAt = target.respawnAt;
      if (source && source.isPlayer) {
        state.gold += 95;
        gainExperience(125);
        if (player) {
          if (player.jungleBuffBonus) player.attackDamage -= player.jungleBuffBonus;
          player.jungleBuffBonus = 12;
          player.attackDamage += player.jungleBuffBonus;
          player.jungleBuffUntil = state.elapsed + 32;
        }
        $("jungle-buff").hidden = false;
        showObjective("КРИСТАЛЛ ПОВЕРЖЕН · +95 ЗОЛОТА · БАФФ 32 СЕКУНДЫ", 3600);
        addCombatText("Получено усиление: следующий удар сильнее", "good");
      }
      return;
    }
    if (target.kind === "hero") {
      if (target.team === RED) {
        state.blueScore += 1;
        if (source && source.team === BLUE) {
          state.kills += source.isPlayer ? 1 : 0;
          if (source.isPlayer) { state.gold += 110; gainExperience(190); }
        }
        target.respawnAt = state.elapsed + 7;
        addCombatText(target === enemyHero ? `Вражеский герой повержен · +110 золота · ${defaultHero(target.heroId).name}` : "Союзник победил героя противника", "good");
      } else {
        state.redScore += 1;
        if (target.isPlayer) {
          state.deaths += 1;
          target.respawnAt = state.elapsed + 6;
          $("respawn-banner").hidden = false;
          addCombatText(`${defaultHero(target.heroId).name} повержен · возвращайся на линию`, "danger");
        } else {
          target.respawnAt = state.elapsed + 7;
          addCombatText("Союзный герой повержен", "danger");
        }
      }
      return;
    }
    if (source && source.isPlayer) {
      state.gold += 28;
      gainExperience(38);
      addCombatText("Вражеский боец повержен · +28 золота · +38 опыта", "good");
    }
  }

  function spawnHit(x, z, color, bright) {
    const group = new THREE.Group();
    group.position.set(x, .12, z);
    const material = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: bright ? .88 : .57, side: THREE.DoubleSide, depthWrite: false });
    const ring = new THREE.Mesh(new THREE.RingGeometry(.21, .32, 18), material);
    ring.rotation.x = -Math.PI / 2;
    group.add(ring);
    const sparks = [];
    if (bright) {
      const mat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .9 });
      for (let i = 0; i < 5; i++) {
        const spark = new THREE.Mesh(new THREE.OctahedronGeometry(.075, 0), mat);
        const a = i * (Math.PI * 2 / 5);
        spark.position.set(Math.cos(a) * .2, .08 + (i % 2) * .1, Math.sin(a) * .2);
        group.add(spark);
        sparks.push(spark);
      }
    }
    scene.add(group);
    effects.push({ object: group, material, sparks, age: 0, life: bright ? .44 : .28, size: bright ? 1.2 : .7, kind: "hit" });
  }

  function spawnBurst(x, z, color, radius) {
    const group = new THREE.Group();
    group.position.set(x, .16, z);
    const ringMaterial = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .9, side: THREE.DoubleSide, depthWrite: false });
    const ring = new THREE.Mesh(new THREE.RingGeometry(.4, .57, 40), ringMaterial);
    ring.rotation.x = -Math.PI / 2;
    group.add(ring);
    const flash = new THREE.Mesh(new THREE.SphereGeometry(.36, 10, 8), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .42, depthWrite: false }));
    flash.position.y = .48;
    group.add(flash);
    scene.add(group);
    effects.push({ object: group, material: ringMaterial, sparks: [], age: 0, life: .58, size: radius, kind: "burst", flash });
  }

  function spawnProjectile(source, target, x, z, damage, color, speed, targetPosition) {
    const mesh = new THREE.Mesh(new THREE.SphereGeometry(.14, 8, 7), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .98 }));
    mesh.position.set(x, source && source.kind === "hero" ? 1.45 : .9, z);
    const glow = new THREE.Mesh(new THREE.SphereGeometry(.3, 8, 6), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .24 }));
    mesh.add(glow);
    const light = new THREE.PointLight(color, .65, 2.2, 2);
    mesh.add(light);
    scene.add(mesh);
    projectiles.push({ mesh, source, target: target || null, targetPosition: targetPosition || null, damage, color, speed: speed || 14, life: 2.3 });
  }

  function targetPosition(target) {
    return v(target.x, target.kind === "hero" ? 1.35 : target.kind === "monster" ? 1.1 : target.type ? 1.65 : .8, target.z);
  }

  function findNearestEnemy(source, maxRange, allowStructures) {
    if (!source) return null;
    let best = null;
    let bestDist = maxRange === undefined ? Infinity : maxRange;
    for (const actor of actors) {
      if (!actor.alive || actor.team === source.team || actor === source) continue;
      const d = distance(source, actor);
      if (d < bestDist) { best = actor; bestDist = d; }
    }
    if (allowStructures) {
      const structure = enemyStructureFor(source.team);
      if (structure && structure.alive) {
        const d = distance(source, structure);
        if (d < bestDist) best = structure;
      }
    }
    return best;
  }

  function basicAttack(source, target) {
    if (!source || !source.alive || !target || !target.alive) return;
    const dx = target.x - source.x;
    const dz = target.z - source.z;
    source.facing = Math.atan2(dx, dz);
    const color = source.team === BLUE ? 0x8dece4 : 0xff8675;
    spawnProjectile(source, target, source.x + Math.sin(source.facing) * .65, source.z + Math.cos(source.facing) * .65, source.attackDamage, color, source.isPlayer ? 16 : 12);
  }

  function canAttackStructure(source, structure, range) {
    if (!structure || !structure.alive || structure.team === source.team) return false;
    if (structure.type === "core") {
      const tower = structures.find((s) => s.team === structure.team && s.type === "tower");
      if (tower && tower.alive) return false;
    }
    return distance(source, structure) <= range;
  }

  function playerAttack() {
    if (!player || !player.alive || state.phase !== "playing" || player.attackCooldown > 0) return;
    let target = findNearestEnemy(player, player.attackRange + .9, false);
    if (!target) {
      const structure = enemyStructureFor(BLUE);
      if (canAttackStructure(player, structure, player.attackRange + .3)) target = structure;
    }
    if (!target) return;
    player.attackCooldown = player.attackDelay;
    basicAttack(player, target);
  }

  function spendMana(cost) {
    if (!player || player.mana < cost) {
      showObjective("НЕДОСТАТОЧНО МАНЫ", 1000);
      return false;
    }
    player.mana -= cost;
    return true;
  }

  function castAbility(key) {
    if (state.phase !== "playing" || !player || !player.alive) return;
    const ability = activeAbilities[key];
    if (!ability || state.cooldowns[key] > 0) return;
    if (!spendMana(ability.cost)) return;
    state.cooldowns[key] = ability.cooldown;
    const target = findNearestEnemy(player, key === "e" ? 10 : 6, true);
    const heroData = defaultHero(player.heroId);
    const palette = window.RiftMeta ? window.RiftMeta.getSkinPalette(player.heroId, selectedSkinId) : heroData.palette;
    const color = key === "w" ? palette.blade : key === "e" ? palette.armor : key === "r" ? palette.trim : palette.glow;
    const baseDamage = ability.damage || 135;

    if (key === "q") {
      const dx = target ? target.x - player.x : Math.sin(player.facing) * 3;
      const dz = target ? target.z - player.z : Math.cos(player.facing) * 3;
      const len = Math.max(.001, Math.hypot(dx, dz));
      player.facing = Math.atan2(dx, dz);
      const cx = player.x + dx / len * 1.65;
      const cz = player.z + dz / len * 1.65;
      spawnBurst(cx, cz, color, 3.25);
      for (const enemy of actors.slice()) {
        if (enemy.alive && enemy.team !== BLUE && distance({ x: cx, z: cz }, enemy) <= 2.35) dealDamage(enemy, Math.round(baseDamage * (enemy.kind === "hero" ? .74 : 1)), player);
      }
      const structure = enemyStructureFor(BLUE);
      if (canAttackStructure(player, structure, 5.2) && distance({ x: cx, z: cz }, structure) <= 2.55) dealDamage(structure, Math.round(baseDamage * .7), player);
    } else if (key === "w") {
      let dx = joystickInput.x;
      let dz = joystickInput.z;
      if (Math.hypot(dx, dz) < .15 && target) { dx = target.x - player.x; dz = target.z - player.z; }
      if (Math.hypot(dx, dz) < .15) { dx = Math.sin(player.facing); dz = Math.cos(player.facing); }
      const len = Math.hypot(dx, dz) || 1;
      dx /= len; dz /= len;
      const oldX = player.x;
      const oldZ = player.z;
      player.x = clamp(player.x + dx * 3.6, -13.5, 13.5);
      player.z = clamp(player.z + dz * 3.6, -13.5, 13.5);
      player.model.position.set(player.x, 0, player.z);
      player.facing = Math.atan2(dx, dz);
      moveTarget = null;
      spawnBurst(oldX, oldZ, color, .9);
      spawnBurst(player.x, player.z, color, 1.7);
      for (const enemy of actors.slice()) {
        if (enemy.alive && enemy.team !== BLUE && distance(player, enemy) < 2.1) dealDamage(enemy, Math.round(baseDamage * (enemy.kind === "hero" ? .58 : .82)), player);
      }
    } else if (key === "e") {
      if (target) {
        spawnProjectile(player, target, player.x, player.z, target.kind === "hero" ? Math.round(baseDamage * .82) : target.type ? Math.round(baseDamage * .68) : Math.round(baseDamage * 1.08), color, 18);
        const dx = target.x - player.x;
        const dz = target.z - player.z;
        player.facing = Math.atan2(dx, dz);
      } else {
        const aimX = clamp(player.x + Math.sin(player.facing) * 8, -13, 13);
        const aimZ = clamp(player.z + Math.cos(player.facing) * 8, -13, 13);
        spawnProjectile(player, null, player.x, player.z, 0, color, 18, { x: aimX, z: aimZ });
      }
    } else if (key === "r") {
      spawnBurst(player.x, player.z, color, 6.5);
      for (const enemy of actors.slice()) {
        if (enemy.alive && enemy.team !== BLUE && distance(player, enemy) <= 6.3) dealDamage(enemy, Math.round(baseDamage * (enemy.kind === "hero" ? .88 : 1)), player);
      }
      const structure = enemyStructureFor(BLUE);
      if (canAttackStructure(player, structure, 7.0) && distance(player, structure) <= 6.3) dealDamage(structure, 150, player);
    }
    if (window.RiftMeta) {
      const frequencies = { q: 480, w: 620, e: 710, r: 360 };
      window.RiftMeta.playUiTone(frequencies[key] || 520, key === "r" ? .36 : .18, "triangle");
      window.RiftMeta.speak(heroData.lines && heroData.lines[key], player.heroId);
    }
    updateCooldownButtons();
  }

  function usePotion() {
    if (state.phase !== "playing" || !player || !player.alive || state.potionUsed) return;
    if (player.hp >= player.maxHp && player.mana >= player.maxMana) {
      showObjective("ЗЕЛЬЕ НУЖНО ВЫПИТЬ В БОЮ", 1300);
      return;
    }
    state.potionUsed = true;
    player.hp = Math.min(player.maxHp, player.hp + 250);
    player.mana = Math.min(player.maxMana, player.mana + 140);
    updateBar(player);
    $("potion-btn").classList.add("used");
    spawnBurst(player.x, player.z, 0x78e1aa, 1.8);
    addCombatText("Зелье восстановило силы", "good");
  }

  function updateAbilities(dt) {
    for (const key of Object.keys(state.cooldowns)) state.cooldowns[key] = Math.max(0, state.cooldowns[key] - dt);
    if (player && player.alive) player.mana = Math.min(player.maxMana, player.mana + dt * 8.5);
    updateCooldownButtons();
  }

  function updateCooldownButtons() {
    for (const key of Object.keys(activeAbilities)) {
      const ability = activeAbilities[key];
      const button = document.querySelector(`[data-ability="${key}"]`);
      if (!button) continue;
      const remaining = state.cooldowns[key] || 0;
      button.classList.toggle("cooling", remaining > .02);
      button.style.setProperty("--cooldown", `${Math.round(remaining / ability.cooldown * 100)}%`);
      const text = button.querySelector(".cooldown-text");
      if (text) text.textContent = remaining > .02 ? `${Math.ceil(remaining)}` : "";
      button.classList.toggle("no-mana", !!player && player.mana < ability.cost);
    }
  }

  function moveActor(actor, dx, dz, dt) {
    const len = Math.hypot(dx, dz);
    if (len < .0001) return;
    dx /= len;
    dz /= len;
    actor.x = clamp(actor.x + dx * actor.speed * dt, -13.65, 13.65);
    actor.z = clamp(actor.z + dz * actor.speed * dt, -13.65, 13.65);
    actor.facing = Math.atan2(dx, dz);
    actor.model.position.set(actor.x, 0, actor.z);
    actor.moved = true;
  }

  function updatePlayer(dt, timeDelta) {
    if (!player || !player.alive) return;
    let dx = joystickInput.x;
    let dz = joystickInput.z;
    const keys = window.__riftKeys || {};
    dx += (keys.arrowright ? 1 : 0) - (keys.arrowleft ? 1 : 0);
    dz += (keys.arrowdown ? 1 : 0) - (keys.arrowup ? 1 : 0);
    if (Math.hypot(dx, dz) > .12) {
      moveTarget = null;
      moveActor(player, dx, dz, dt);
    } else if (moveTarget) {
      const tx = moveTarget.x - player.x;
      const tz = moveTarget.z - player.z;
      if (Math.hypot(tx, tz) < .3) moveTarget = null;
      else moveActor(player, tx, tz, dt);
    }
    player.attackCooldown = Math.max(0, player.attackCooldown - timeDelta);
    if (attackHeld) playerAttack();
  }

  function updateEnemyHero(dt, timeDelta) {
    const bot = enemyHero;
    if (!bot || !bot.alive) return;
    bot.attackCooldown = Math.max(0, bot.attackCooldown - timeDelta);
    let target = null;
    if (player && player.alive && distance(bot, player) < 11.5) target = player;
    if (!target) target = actors.filter((a) => a.alive && a.team === BLUE && a !== player).sort((a, b) => distance(bot, a) - distance(bot, b))[0] || null;
    if (target) {
      const d = distance(bot, target);
      if (d > bot.attackRange * .86) moveActor(bot, target.x - bot.x, target.z - bot.z, dt);
      else if (bot.attackCooldown <= 0) {
        bot.attackCooldown = bot.attackDelay;
        basicAttack(bot, target);
      }
    } else {
      const structure = enemyStructureFor(RED);
      if (structure && distance(bot, structure) > 2.4) moveActor(bot, structure.x - bot.x, structure.z - bot.z, dt);
    }
  }

  function updateNeutralMonster(dt, timeDelta) {
    const monsters = actors.filter((actor) => actor.kind === "monster");
    for (const monster of monsters) {
      if (!monster.alive) continue;
      monster.attackCooldown = Math.max(0, monster.attackCooldown - timeDelta);
      if (!player || !player.alive || distance(monster, player) > 7.5) {
        if (distance(monster, { x: monster.spawnX, z: monster.spawnZ }) > .6) moveActor(monster, monster.spawnX - monster.x, monster.spawnZ - monster.z, dt);
        continue;
      }
      const d = distance(monster, player);
      if (d > 2.15) {
        moveActor(monster, player.x - monster.x, player.z - monster.z, dt);
      } else if (monster.attackCooldown <= 0) {
        monster.attackCooldown = monster.attackDelay;
        spawnProjectile(monster, player, monster.x, monster.z, monster.attackDamage, 0xb98ce4, 9);
      }
    }
  }

  function minionTarget(minion) {
    let best = null;
    let bestDistance = 2.45;
    for (const actor of actors) {
      if (!actor.alive || actor.team === "neutral" || actor.team === minion.team || actor.kind === "minion" && distance(minion, actor) > bestDistance) continue;
      const d = distance(minion, actor);
      if (d < bestDistance) { best = actor; bestDistance = d; }
    }
    return best;
  }

  function updateMinion(minion, dt, timeDelta) {
    if (!minion.alive) return;
    minion.attackCooldown = Math.max(0, minion.attackCooldown - timeDelta);
    const target = minionTarget(minion);
    if (target) {
      minion.facing = Math.atan2(target.x - minion.x, target.z - minion.z);
      if (minion.attackCooldown <= 0) {
        minion.attackCooldown = minion.attackDelay;
        spawnProjectile(minion, target, minion.x, minion.z, minion.attackDamage, minion.team === BLUE ? 0x74d5df : 0xf38470, 9);
      }
      return;
    }
    const structure = enemyStructureFor(minion.team);
    if (!structure || !structure.alive) return;
    const dx = structure.x - minion.x;
    const dz = structure.z - minion.z;
    const d = Math.hypot(dx, dz);
    if (d > 2.55) {
      moveActor(minion, dx, dz, dt);
    } else if (minion.attackCooldown <= 0) {
      minion.attackCooldown = 1.3;
      structure.hp = Math.max(0, structure.hp - 23);
      updateBar(structure);
      if (structure.hp <= 0) dealDamage(structure, 0, minion);
      else if (Math.floor(state.elapsed) % 8 === 0 && minion.team === BLUE) addCombatText("Союзные бойцы атакуют башню", "good");
    }
  }

  function spawnWave() {
    waveNumber += 1;
    const stagger = waveNumber % 2 ? .68 : .84;
    for (let i = 0; i < 3; i++) {
      makeActor(BLUE, "minion", -9.0 - i * stagger, -9.0 - i * stagger, false);
      makeActor(RED, "minion", 9.0 + i * stagger, 9.0 + i * stagger, false);
    }
    addCombatText(`Волна ${waveNumber} выдвинулась по линии`, "good");
  }

  function updateStructures(dt, timeDelta) {
    for (const structure of structures) {
      if (!structure.alive || structure.type !== "tower") continue;
      structure.cooldown = Math.max(0, structure.cooldown - timeDelta);
      if (structure.cooldown > 0) continue;
      let target = null;
      let targetDist = 7.3;
      for (const actor of actors) {
        if (!actor.alive || actor.team === "neutral" || actor.team === structure.team) continue;
        const d = distance(structure, actor);
        if (d < targetDist) { target = actor; targetDist = d; }
      }
      if (target) {
        structure.cooldown = 1.45;
        spawnProjectile({ team: structure.team, kind: "tower", x: structure.x, z: structure.z }, target, structure.x, structure.z, target.kind === "hero" ? 42 : 61, structure.team === BLUE ? 0x69d8ed : 0xff8b78, 15);
      }
    }
  }

  function updateProjectiles(dt) {
    for (let i = projectiles.length - 1; i >= 0; i--) {
      const p = projectiles[i];
      p.life -= dt;
      let target = p.target;
      let tx;
      let tz;
      let ty;
      if (target && target.alive) {
        tx = target.x; tz = target.z; ty = target.kind === "hero" ? 1.35 : target.kind === "monster" ? 1.1 : target.type ? 1.45 : .8;
      } else if (p.targetPosition) {
        tx = p.targetPosition.x; tz = p.targetPosition.z; ty = .8;
      } else {
        scene.remove(p.mesh);
        clearTree(p.mesh);
        projectiles.splice(i, 1);
        continue;
      }
      const dx = tx - p.mesh.position.x;
      const dy = ty - p.mesh.position.y;
      const dz = tz - p.mesh.position.z;
      const d = Math.hypot(dx, dy, dz);
      if (d < Math.max(.24, p.speed * dt)) {
        if (target && target.alive && p.damage > 0) dealDamage(target, p.damage, p.source);
        else if (p.damage > 0) spawnHit(tx, tz, p.color, false);
        scene.remove(p.mesh);
        clearTree(p.mesh);
        projectiles.splice(i, 1);
      } else {
        p.mesh.position.x += dx / d * p.speed * dt;
        p.mesh.position.y += dy / d * p.speed * dt;
        p.mesh.position.z += dz / d * p.speed * dt;
      }
      if (p.life <= 0) {
        scene.remove(p.mesh);
        clearTree(p.mesh);
        projectiles.splice(i, 1);
      }
    }
  }

  function updateEffects(dt) {
    for (let i = effects.length - 1; i >= 0; i--) {
      const fx = effects[i];
      fx.age += dt;
      const t = clamp(fx.age / fx.life, 0, 1);
      if (fx.kind === "burst") {
        const scale = .45 + t * fx.size;
        fx.object.scale.set(scale, scale, scale);
        fx.material.opacity = Math.max(0, .9 * (1 - t));
        if (fx.flash) { fx.flash.scale.setScalar(.5 + (1 - t) * 1.8); fx.flash.material.opacity = .38 * (1 - t); }
      } else {
        const scale = .65 + t * fx.size;
        fx.object.scale.set(scale, scale, scale);
        fx.material.opacity = Math.max(0, (fx.kind === "hit" ? .84 : .7) * (1 - t));
        fx.sparks.forEach((spark, j) => { spark.position.y += dt * (j % 2 ? 1 : .65); spark.rotation.y += dt * 4; });
      }
      if (fx.age >= fx.life) {
        scene.remove(fx.object);
        clearTree(fx.object);
        effects.splice(i, 1);
      }
    }
  }

  function updateRespawns() {
    for (const actor of actors) {
      if (actor.alive || !actor.respawnAt || state.elapsed < actor.respawnAt) continue;
      actor.alive = true;
      actor.hp = actor.maxHp;
      actor.mana = actor.maxMana;
      actor.attackCooldown = .5;
      if (actor.kind === "monster") {
        actor.x = actor.spawnX; actor.z = actor.spawnZ;
        actor.model.position.set(actor.x, 0, actor.z);
        actor.model.visible = true;
        actor.respawnAt = 0; actor.removeAt = 0;
        updateBar(actor);
        addCombatText("Нейтральный кристалл снова пробуждается", "good");
        continue;
      }
      if (actor === player) {
        actor.x = -4.1; actor.z = -4.1;
        $("respawn-banner").hidden = true;
        addCombatText(`${defaultHero(actor.heroId).name} вернулся в бой`, "good");
      } else if (actor.team === RED) {
        actor.x = 3.4; actor.z = 3.4;
        addCombatText("Вражеский герой снова в бою", "danger");
      } else {
        actor.x = -6.4; actor.z = -6.4;
      }
      actor.model.position.set(actor.x, 0, actor.z);
      actor.model.visible = true;
      actor.respawnAt = 0;
      updateBar(actor);
    }
    if (player && !player.alive) {
      $("respawn-banner").hidden = false;
      const remaining = Math.max(0, Math.ceil(player.respawnAt - state.elapsed));
      $("respawn-banner").innerHTML = `ВОЗРОЖДЕНИЕ ЧЕРЕЗ <b>${remaining}</b>`;
    }
  }

  function updateCleanup() {
    if (state.elapsed < nextCleanup) return;
    nextCleanup = state.elapsed + .75;
    for (let i = actors.length - 1; i >= 0; i--) {
      const actor = actors[i];
      if (actor.alive || actor.respawnAt > state.elapsed || actor.removeAt > state.elapsed || actor === player || actor === enemyHero) continue;
      scene.remove(actor.model);
      clearTree(actor.model);
      actors.splice(i, 1);
    }
  }

  function updateCamera(dt) {
    let targetX = -1.2;
    let targetZ = -.25;
    if ((state.phase === "playing" || state.phase === "paused" || state.phase === "result") && player) {
      targetX = player.alive ? player.x : -4.1;
      targetZ = player.alive ? player.z : -4.1;
    }
    cameraTarget.x += (targetX - cameraTarget.x) * Math.min(1, dt * 3.2);
    cameraTarget.z += (targetZ - cameraTarget.z) * Math.min(1, dt * 3.2);
    const heightView = state.phase === "lobby" ? 11.5 : 20.5;
    const aspect = window.innerWidth / Math.max(1, window.innerHeight);
    const wantedX = cameraTarget.x;
    const wantedY = cameraTarget.y + heightView * 1.38;
    const wantedZ = cameraTarget.z + heightView * 1.22;
    const smooth = Math.min(1, dt * (state.phase === "lobby" ? 1.4 : 4.2));
    camera.position.x += (wantedX - camera.position.x) * smooth;
    camera.position.y += (wantedY - camera.position.y) * smooth;
    camera.position.z += (wantedZ - camera.position.z) * smooth;
    camera.lookAt(cameraTarget.x, 0, cameraTarget.z);
    if (aspect < .9) camera.zoom = .86;
    else camera.zoom = 1;
    camera.updateProjectionMatrix();
  }

  function plotMap(element, x, z) {
    if (!element) return;
    const left = clamp((x + WORLD_EDGE) / (WORLD_EDGE * 2), .07, .93) * 100;
    const top = clamp((z + WORLD_EDGE) / (WORLD_EDGE * 2), .07, .93) * 100;
    element.style.left = `${left}%`;
    element.style.top = `${top}%`;
  }

  function renderHud() {
    if (!player) return;
    $("hero-hp-bar").style.width = `${clamp(player.hp / player.maxHp, 0, 1) * 100}%`;
    $("hero-mana-bar").style.width = `${clamp(player.mana / player.maxMana, 0, 1) * 100}%`;
    $("hero-xp-bar").style.width = `${clamp(player.xp / player.nextLevelXp, 0, 1) * 100}%`;
    $("level-badge").textContent = String(player.level);
    $("gold-value").innerHTML = `${state.gold.toLocaleString("ru-RU")} <i>✦</i>`;
    $("blue-score").textContent = state.blueScore;
    $("red-score").textContent = state.redScore;
    $("match-time").textContent = fmtTime(state.elapsed);
    plotMap($("player-dot"), player.x, player.z);
    if (enemyHero && enemyHero.alive) {
      plotMap($("enemy-dot"), enemyHero.x, enemyHero.z);
      $("enemy-dot").style.opacity = "1";
    } else {
      $("enemy-dot").style.opacity = ".35";
    }
    const neutral = actors.find((a) => a.kind === "monster");
    if (neutral) {
      plotMap($("neutral-dot"), neutral.x, neutral.z);
      $("neutral-dot").style.opacity = neutral.alive ? "1" : ".35";
    }
    const firstMinion = actors.find((a) => a.kind === "minion" && a.team === BLUE && a.alive);
    if (firstMinion) plotMap($("minimap-wave"), firstMinion.x, firstMinion.z);
    updateCooldownButtons();
  }

  function update(dt, timeDelta) {
    worldTime += timeDelta;
    if (menuHero && state.phase === "lobby") {
      menuHero.rotation.y += timeDelta * .12;
      const vModel = menuHero.userData.visual;
      if (vModel) vModel.position.y = .035 + Math.sin(worldTime * 1.15) * .045;
    }
    if (state.phase === "playing") {
      state.elapsed += timeDelta;
      updateAbilities(timeDelta);
      updatePlayer(dt, timeDelta);
      updateEnemyHero(dt, timeDelta);
      updateNeutralMonster(dt, timeDelta);
      if (player && player.jungleBuffUntil && state.elapsed >= player.jungleBuffUntil) {
        player.attackDamage = Math.max(1, player.attackDamage - (player.jungleBuffBonus || 0));
        player.jungleBuffUntil = 0; player.jungleBuffBonus = 0;
        $("jungle-buff").hidden = true;
        addCombatText("Усиление кристалла закончилось", "good");
      }
      const minions = actors.filter((actor) => actor.kind === "minion");
      for (const minion of minions) updateMinion(minion, dt, timeDelta);
      updateStructures(dt, timeDelta);
      updateProjectiles(dt);
      updateEffects(timeDelta);
      updateRespawns();
      updateCleanup();
      if (state.elapsed - lastWave >= 19) {
        lastWave = state.elapsed;
        spawnWave();
      }
      if (state.elapsed > 0 && Math.floor(state.elapsed) !== Math.floor(state.elapsed - timeDelta)) renderHud();
      else if (Math.floor(worldTime * 8) !== Math.floor((worldTime - timeDelta) * 8)) renderHud();
    } else if (state.phase !== "paused") {
      updateEffects(timeDelta);
      if (state.phase === "result" && projectiles.length) updateProjectiles(dt);
    }
    updateCamera(dt);
  }

  function animate() {
    requestAnimationFrame(animate);
    const rawDelta = clock.getDelta();
    const dt = Math.min(rawDelta, .25);
    const timeDelta = Math.min(rawDelta, .75);
    update(dt, timeDelta);
    renderer.render(scene, camera);
  }
  animate();

  function bindPointerHold(button, callbackDown, callbackUp) {
    if (!("PointerEvent" in window)) {
      button.addEventListener("click", (event) => {
        if (callbackDown) callbackDown(event);
        if (callbackUp) window.setTimeout(() => callbackUp(event), 140);
      });
      return;
    }
    button.addEventListener("pointerdown", (event) => {
      event.preventDefault();
      if (button.setPointerCapture) button.setPointerCapture(event.pointerId);
      if (callbackDown) callbackDown(event);
    });
    const release = (event) => {
      if (callbackUp) callbackUp(event);
    };
    button.addEventListener("pointerup", release);
    button.addEventListener("pointercancel", release);
    button.addEventListener("lostpointercapture", release);
  }

  const joystick = $("joystick");
  const knob = $("joystick-knob");
  let joyPointer = null;
  function updateJoystick(event) {
    const rect = joystick.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const max = rect.width * .33;
    let dx = event.clientX - cx;
    let dy = event.clientY - cy;
    const len = Math.hypot(dx, dy);
    if (len > max) { dx = dx / len * max; dy = dy / len * max; }
    knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    joystickInput.x = dx / max;
    joystickInput.z = dy / max;
  }
  joystick.addEventListener("pointerdown", (event) => {
    if (state.phase !== "playing") return;
    event.preventDefault();
    joyPointer = event.pointerId;
    if (joystick.setPointerCapture) joystick.setPointerCapture(joyPointer);
    joystick.classList.add("active");
    updateJoystick(event);
  });
  joystick.addEventListener("pointermove", (event) => { if (event.pointerId === joyPointer) updateJoystick(event); });
  const releaseJoystick = (event) => {
    if (event.pointerId !== joyPointer) return;
    joyPointer = null;
    joystickInput = { x: 0, z: 0 };
    knob.style.transform = "translate(-50%, -50%)";
    joystick.classList.remove("active");
  };
  joystick.addEventListener("pointerup", releaseJoystick);
  joystick.addEventListener("pointercancel", releaseJoystick);
  joystick.addEventListener("lostpointercapture", releaseJoystick);
  if (!("PointerEvent" in window)) {
    let joyTouchId = null;
    const matchingTouch = (list) => {
      for (let i = 0; i < list.length; i++) if (list[i].identifier === joyTouchId) return list[i];
      return null;
    };
    joystick.addEventListener("touchstart", (event) => {
      if (state.phase !== "playing" || !event.changedTouches.length) return;
      const touch = event.changedTouches[0];
      joyTouchId = touch.identifier;
      event.preventDefault();
      joystick.classList.add("active");
      updateJoystick(touch);
    }, { passive: false });
    joystick.addEventListener("touchmove", (event) => {
      const touch = matchingTouch(event.changedTouches);
      if (!touch) return;
      event.preventDefault();
      updateJoystick(touch);
    }, { passive: false });
    const releaseTouch = (event) => {
      const touch = matchingTouch(event.changedTouches);
      if (!touch) return;
      joyTouchId = null;
      joystickInput = { x: 0, z: 0 };
      knob.style.transform = "translate(-50%, -50%)";
      joystick.classList.remove("active");
      event.preventDefault();
    };
    joystick.addEventListener("touchend", releaseTouch, { passive: false });
    joystick.addEventListener("touchcancel", releaseTouch, { passive: false });
  }

  bindPointerHold($("attack-btn"), () => {
    if (state.phase !== "playing") return;
    attackHeld = true;
    toggleButton($("attack-btn"), true);
    playerAttack();
  }, () => {
    attackHeld = false;
    toggleButton($("attack-btn"), false);
  });
  document.querySelectorAll("[data-ability]").forEach((button) => {
    const key = button.dataset.ability;
    button.addEventListener("pointerdown", (event) => { event.preventDefault(); castAbility(key); });
    button.addEventListener("click", (event) => { if (!("PointerEvent" in window) || event.detail === 0) castAbility(key); });
  });
  $("potion-btn").addEventListener("pointerdown", (event) => { event.preventDefault(); usePotion(); });
  $("potion-btn").addEventListener("click", (event) => { if (!("PointerEvent" in window) || event.detail === 0) usePotion(); });

  $("play-btn").addEventListener("click", startMatch);
  const modeCard = $("mode-card");
  if (modeCard) modeCard.addEventListener("click", startMatch);
  $("pause-btn").addEventListener("click", pauseMatch);
  $("resume-btn").addEventListener("click", resumeMatch);
  $("leave-btn").addEventListener("click", returnToLobby);
  $("replay-btn").addEventListener("click", startMatch);
  $("menu-btn").addEventListener("click", returnToLobby);
  document.querySelectorAll("[data-nav]").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("[data-nav]").forEach((nav) => nav.classList.toggle("active", nav === button));
      const destination = button.dataset.nav;
      if (destination === "play") startMatch();
      else if (destination === "heroes" && window.RiftMeta) window.RiftMeta.openHeroes();
      else if (destination === "about") showObjective("ОРИГИНАЛЬНАЯ 3D MOBA · ОДИНОЧНАЯ ТРЕНИРОВКА ПРОТИВ ИИ", 2300);
    });
  });

  window.__riftKeys = {};
  window.addEventListener("keydown", (event) => {
    const key = event.key.toLowerCase();
    if (["arrowup", "arrowdown", "arrowleft", "arrowright", " "].includes(key)) event.preventDefault();
    if (key === "escape") {
      if (state.phase === "playing") pauseMatch();
      else if (state.phase === "paused") resumeMatch();
      return;
    }
    if (["arrowup", "arrowdown", "arrowleft", "arrowright"].includes(key)) window.__riftKeys[key] = true;
    if (key === " ") {
      attackHeld = true;
      toggleButton($("attack-btn"), true);
    }
    if (["q", "w", "e", "r"].includes(key)) castAbility(key);
    if (key === "1") usePotion();
  });
  window.addEventListener("keyup", (event) => {
    const key = event.key.toLowerCase();
    if (["arrowup", "arrowdown", "arrowleft", "arrowright"].includes(key)) window.__riftKeys[key] = false;
    if (key === " ") {
      attackHeld = false;
      toggleButton($("attack-btn"), false);
    }
  });
  window.addEventListener("blur", () => {
    window.__riftKeys = {};
    attackHeld = false;
    toggleButton($("attack-btn"), false);
  });
  document.addEventListener("contextmenu", (event) => event.preventDefault());

  renderer.domElement.addEventListener("pointerdown", (event) => {
    if (state.phase !== "playing" || event.pointerType !== "mouse" || event.button !== 0) return;
    pointer.x = event.clientX / window.innerWidth * 2 - 1;
    pointer.y = -(event.clientY / window.innerHeight) * 2 + 1;
    raycaster.setFromCamera(pointer, camera);
    const point = new THREE.Vector3();
    if (raycaster.ray.intersectPlane(groundPick, point) && Math.abs(point.x) < 13.5 && Math.abs(point.z) < 13.5) moveTarget = { x: point.x, z: point.z };
  });

  // Android's hardware Back button calls this through the WebView.
  window.gameBack = () => {
    for (const overlayId of ["heroes-overlay", "account-overlay", "settings-overlay"]) {
      const overlay = $(overlayId);
      if (overlay && !overlay.hidden) { overlay.hidden = true; document.body.classList.remove("meta-open"); return true; }
    }
    if (state.phase === "playing") { pauseMatch(); return true; }
    if (state.phase === "paused") { resumeMatch(); return true; }
    if (state.phase === "result") { returnToLobby(); return true; }
    return false;
  };
})();
