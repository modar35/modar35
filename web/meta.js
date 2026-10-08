/* Лобби, герои, аккаунт, прогресс и настройки Rift Arena. */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const STORAGE = {
    hero: "rift.hero.v1", skin: "rift.skin.v1", settings: "rift.settings.v1",
    token: "rift.account.token.v1", localProfile: "rift.profile.local.v1"
  };
  const MEDALS = [
    { id: "first_match", name: "Первый бой", icon: "✦", text: "Завершить первый матч" },
    { id: "first_win", name: "Первая победа", icon: "♛", text: "Победить в матче" },
    { id: "slayer", name: "Охотник", icon: "⚔", text: "5 убийств за матч" },
    { id: "unbroken", name: "Несокрушимый", icon: "⬡", text: "Победа с 0–1 смертью" },
    { id: "veteran", name: "Ветеран", icon: "⌖", text: "Сыграть 10 матчей" },
    { id: "champion", name: "Чемпион", icon: "♜", text: "Одержать 5 побед" }
  ];
  const HEROES = {
    elaris: {
      id: "elaris", name: "Эларис", title: "Хранительница рассвета", role: "МАГ", difficulty: "СРЕДНЯЯ", symbol: "Э",
      intro: "Маг дальнего боя: контролирует пространство сияющими осколками.", voice: { pitch: 1.12, rate: .92 },
      stats: { hp: 780, mana: 500, speed: 3.65, attack: 39, delay: .78, range: 4.0 },
      palette: { primary: 0x276a77, armor: 0x4fa8ad, trim: 0xd6b878, dark: 0x182e32, blade: 0xcdf7e9, glow: 0x72eee3 },
      skins: [
        { id: "dawn", name: "Рассветная стража", colors: { primary: 0x276a77, armor: 0x4fa8ad, trim: 0xd6b878, blade: 0xcdf7e9, glow: 0x72eee3 } },
        { id: "eclipse", name: "Лунное затмение", colors: { primary: 0x3b315d, armor: 0x77649b, trim: 0xc1a6ed, blade: 0xd4bcff, glow: 0xcba9ff } }
      ],
      abilities: {
        q: { cooldown: 6, cost: 55, name: "Рассветный осколок", damage: 135 },
        w: { cooldown: 10, cost: 70, name: "Скачок по лучу", damage: 105 },
        e: { cooldown: 8, cost: 70, name: "Солнечная сфера", damage: 175 },
        r: { cooldown: 28, cost: 115, name: "Сияние", damage: 245 }
      },
      lines: { start: "Свет держит строй. Вперёд!", q: "Осколок рассвета!", w: "Сквозь сумрак!", e: "Сияй!", r: "Пусть разлом увидит рассвет!", win: "Свет победил. На этот раз." }
    },
    raen: {
      id: "raen", name: "Раэн", title: "Клинок тихой бури", role: "УБИЙЦА", difficulty: "ВЫСОКАЯ", symbol: "Р",
      intro: "Быстрый боец ближнего боя: наказывает за ошибку и уходит из-под удара.", voice: { pitch: .88, rate: 1.12 },
      stats: { hp: 680, mana: 420, speed: 4.35, attack: 47, delay: .68, range: 3.2 },
      palette: { primary: 0x4b3a66, armor: 0x82639a, trim: 0x7fe0d0, dark: 0x18212c, blade: 0xbaf8e7, glow: 0x81efdc },
      skins: [
        { id: "gale", name: "Буревестник", colors: { primary: 0x4b3a66, armor: 0x82639a, trim: 0x7fe0d0, blade: 0xbaf8e7, glow: 0x81efdc } },
        { id: "ember", name: "Пепельный странник", colors: { primary: 0x513438, armor: 0xa65d4b, trim: 0xf0bc79, blade: 0xffd0a1, glow: 0xff986f } }
      ],
      abilities: {
        q: { cooldown: 5, cost: 42, name: "Двойной разрез", damage: 160 },
        w: { cooldown: 8, cost: 48, name: "Шаг сквозь тень", damage: 125 },
        e: { cooldown: 9, cost: 58, name: "Метательный клинок", damage: 190 },
        r: { cooldown: 25, cost: 100, name: "Буря клинков", damage: 230 }
      },
      lines: { start: "Не отставай от тени.", q: "Два удара. Один шанс.", w: "Меня здесь нет.", e: "Лети, клинок!", r: "Буря, сомкнись!", win: "Следы закончились. Победа." }
    },
    varkor: {
      id: "varkor", name: "Варкор", title: "Страж каменного сердца", role: "ТАНК", difficulty: "НИЗКАЯ", symbol: "В",
      intro: "Тяжёлый защитник: выдерживает давление и открывает союзникам путь.", voice: { pitch: .72, rate: .82 },
      stats: { hp: 1080, mana: 430, speed: 3.05, attack: 34, delay: .96, range: 3.5 },
      palette: { primary: 0x536050, armor: 0x87916b, trim: 0xd9b76e, dark: 0x282e2b, blade: 0xe8dcab, glow: 0xd9b76e },
      skins: [
        { id: "granite", name: "Сердце гранита", colors: { primary: 0x536050, armor: 0x87916b, trim: 0xd9b76e, blade: 0xe8dcab, glow: 0xd9b76e } },
        { id: "deepstone", name: "Глубинный обсидиан", colors: { primary: 0x29323a, armor: 0x536e7d, trim: 0x7fced1, blade: 0xb9fbec, glow: 0x73ddd4 } }
      ],
      abilities: {
        q: { cooldown: 7, cost: 50, name: "Раскол земли", damage: 155 },
        w: { cooldown: 12, cost: 64, name: "Таран стража", damage: 118 },
        e: { cooldown: 10, cost: 62, name: "Каменный заслон", damage: 155 },
        r: { cooldown: 32, cost: 125, name: "Гнев разлома", damage: 260 }
      },
      lines: { start: "За мной. Камень выдержит.", q: "Земля дрогнет!", w: "Держать строй!", e: "Щит поднят.", r: "Разлом, ответь мне!", win: "Наш рубеж устоял." }
    }
  };

  function read(key, fallback) {
    try { const value = localStorage.getItem(key); return value === null ? fallback : JSON.parse(value); }
    catch (_) { return fallback; }
  }
  function write(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); return true; }
    catch (_) { return false; }
  }
  function defaultSettings() {
    return { quality: "auto", music: .22, effects: .65, voice: true, apiBase: "" };
  }
  let settings = Object.assign(defaultSettings(), read(STORAGE.settings, {}));
  const savedHeroId = read(STORAGE.hero, "elaris");
  let selectedHeroId = Object.prototype.hasOwnProperty.call(HEROES, savedHeroId) ? savedHeroId : "elaris";
  let selectedSkinId = read(STORAGE.skin, "dawn");
  if (!HEROES[selectedHeroId].skins.some((item) => item.id === selectedSkinId)) selectedSkinId = HEROES[selectedHeroId].skins[0].id;
  let token = "";
  try { token = localStorage.getItem(STORAGE.token) || ""; } catch (_) { /* storage can be disabled */ }
  let remoteProfile = null;
  let localProfile = read(STORAGE.localProfile, {
    username: "Странник", level: 1, xp: 0, matches: 0, wins: 0, losses: 0,
    kills: 0, deaths: 0, assists: 0, medals: []
  });
  let authMode = "login";
  let audioContext = null;

  function hero() { return HEROES[selectedHeroId] || HEROES.elaris; }
  function skin() { return hero().skins.find((item) => item.id === selectedSkinId) || hero().skins[0]; }
  function profile() { return remoteProfile || localProfile; }
  function apiBase() {
    const manual = String(settings.apiBase || "").trim().replace(/\/+$/, "");
    if (manual) {
      try {
        const url = new URL(manual);
        const localHost = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
        if (url.protocol !== "https:" && !localHost) return "";
        return url.origin;
      } catch (_) { return ""; }
    }
    if (location.protocol === "https:" || location.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname)) return location.origin;
    return "";
  }
  function api(path, options) {
    const base = apiBase();
    if (!base) return Promise.reject(new Error("Для аккаунта укажите HTTPS-адрес сервера профиля в настройках."));
    const request = Object.assign({ cache: "no-store" }, options || {});
    const headers = Object.assign({ "Content-Type": "application/json" }, options && options.headers || {});
    if (token) headers.Authorization = `Bearer ${token}`;
    request.headers = headers;
    return fetch(`${base}${path}`, request).then(async (response) => {
      let data = {};
      try { data = await response.json(); } catch (_) { /* empty response */ }
      if (!response.ok) {
        const error = new Error(data.error || `Ошибка сервера (${response.status}).`);
        error.status = response.status;
        throw error;
      }
      return data;
    });
  }
  function setToken(value) {
    token = value || "";
    try {
      if (token) localStorage.setItem(STORAGE.token, token);
      else localStorage.removeItem(STORAGE.token);
    } catch (_) { /* storage can be disabled */ }
  }
  function formatRate(value) { return `${Math.round(value || 0)}%`; }
  function updateHeader() {
    const p = profile();
    const name = p.username || "Странник";
    const level = Math.max(1, Number(p.level) || 1);
    if ($("profile-name")) $("profile-name").textContent = String(name).slice(0, 20).toLocaleUpperCase("ru-RU");
    if ($("profile-level")) $("profile-level").textContent = token ? `Уровень ${level}` : `Локальный профиль · ${level} ур.`;
    if ($("profile-level-number")) $("profile-level-number").textContent = String(level).padStart(2, "0");
    if ($("account-dot")) $("account-dot").classList.toggle("offline", !token);
  }
  function setHeroText() {
    const h = hero();
    const s = skin();
    if ($("hero-emblem")) $("hero-emblem").textContent = h.symbol;
    if ($("hero-role")) $("hero-role").textContent = `${h.role} · ${h.difficulty}`;
    if ($("hero-selected-name")) $("hero-selected-name").textContent = h.name.toLocaleUpperCase("ru-RU");
    if ($("hero-selected-title")) $("hero-selected-title").textContent = `${h.title} · ${s.name}`;
    if ($("showcase-count")) $("showcase-count").textContent = `${String(Object.keys(HEROES).indexOf(h.id) + 1).padStart(2, "0")} / 03`;
    if ($("showcase-name")) $("showcase-name").textContent = h.name.toLocaleUpperCase("ru-RU");
    if ($("showcase-tagline")) $("showcase-tagline").textContent = h.title.toLocaleUpperCase("ru-RU");
    if ($("showcase-difficulty")) $("showcase-difficulty").textContent = h.difficulty;
    if ($("showcase-role")) $("showcase-role").textContent = h.role;
    if (typeof window.setShowcaseHero === "function") window.setShowcaseHero(h.id, s.id);
    updateHeader();
  }
  function selectHero(id) {
    if (!HEROES[id]) return;
    selectedHeroId = id;
    selectedSkinId = HEROES[id].skins[0].id;
    write(STORAGE.hero, id);
    write(STORAGE.skin, selectedSkinId);
    setHeroText();
    renderHeroPicker();
  }
  function selectSkin(id) {
    if (!hero().skins.some((item) => item.id === id)) return;
    selectedSkinId = id;
    write(STORAGE.skin, id);
    setHeroText();
    renderHeroPicker();
  }
  function renderHeroPicker() {
    const grid = $("hero-select-grid");
    if (!grid) return;
    grid.replaceChildren();
    for (const h of Object.values(HEROES)) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `hero-option${h.id === selectedHeroId ? " selected" : ""}`;
      button.dataset.hero = h.id;
      button.innerHTML = `<span class="hero-option-glyph">${h.symbol}</span><span class="hero-option-copy"><b>${h.name}</b><small>${h.role} · ${h.difficulty}</small></span><span class="hero-option-check">${h.id === selectedHeroId ? "✓" : ""}</span>`;
      button.addEventListener("click", () => selectHero(h.id));
      grid.appendChild(button);
    }
    const detail = $("hero-detail-copy");
    if (detail) detail.textContent = hero().intro;
    const statline = $("hero-statline");
    if (statline) {
      statline.replaceChildren();
      const stats = hero().stats;
      [["ЖИВУЧЕСТЬ", `${stats.hp}`, stats.hp / 11], ["МОБИЛЬНОСТЬ", `${stats.speed.toFixed(1)}`, stats.speed / 4.8 * 100], ["АТАКА", `${stats.attack}`, stats.attack / 55 * 100]].forEach(([label, value, percent]) => {
        const item = document.createElement("div"); item.className = "hero-stat";
        const top = document.createElement("span"); top.innerHTML = `<b>${label}</b><small>${value}</small>`;
        const bar = document.createElement("i"); const fill = document.createElement("em"); fill.style.width = `${Math.max(8, Math.min(100, percent))}%`; bar.appendChild(fill); item.append(top, bar); statline.appendChild(item);
      });
    }
    const abilityList = $("hero-ability-list");
    if (abilityList) {
      abilityList.replaceChildren();
      ["q", "w", "e", "r"].forEach((key) => {
        const badge = document.createElement("span");
        badge.innerHTML = `<b>${key.toUpperCase()}</b> ${hero().abilities[key].name}`;
        abilityList.appendChild(badge);
      });
    }
    const skinGrid = $("skin-select-grid");
    if (skinGrid) {
      skinGrid.replaceChildren();
      for (const s of hero().skins) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = `skin-option${s.id === selectedSkinId ? " selected" : ""}`;
        button.textContent = s.name;
        button.addEventListener("click", () => selectSkin(s.id));
        skinGrid.appendChild(button);
      }
    }
  }
  function openOverlay(id) {
    const overlay = $(id);
    if (!overlay) return;
    overlay.hidden = false;
    document.body.classList.add("meta-open");
    const focusTarget = overlay.querySelector("button, input, select");
    if (focusTarget) setTimeout(() => focusTarget.focus(), 30);
  }
  function closeOverlay(id) {
    if ($(id)) $(id).hidden = true;
    if (!["heroes-overlay", "account-overlay", "settings-overlay"].some((overlayId) => $(overlayId) && !$(overlayId).hidden)) document.body.classList.remove("meta-open");
  }
  function showAuthMessage(message, error) {
    const node = $("auth-message");
    if (!node) return;
    node.textContent = message || "";
    node.classList.toggle("error", !!error);
    node.classList.toggle("success", !!message && !error);
  }
  function renderMedals(container, ids) {
    if (!container) return;
    const earned = new Set((ids || []).map((medal) => typeof medal === "string" ? medal : medal.id));
    container.replaceChildren();
    for (const medal of MEDALS) {
      const unlocked = earned.has(medal.id);
      const item = document.createElement("div");
      item.className = `medal-card${unlocked ? " earned" : " locked"}`;
      item.title = `${medal.name}: ${medal.text}`;
      const glyph = document.createElement("span");
      glyph.className = "medal-icon";
      glyph.textContent = medal.icon;
      const label = document.createElement("b");
      label.textContent = medal.name;
      const hint = document.createElement("small");
      hint.textContent = unlocked ? "ПОЛУЧЕНА" : medal.text;
      item.append(glyph, label, hint);
      container.appendChild(item);
    }
  }
  function renderProfilePanel() {
    const p = profile();
    const matches = Number(p.matches) || 0;
    const wins = Number(p.wins) || 0;
    const rate = p.winRate === undefined ? (matches ? wins / matches * 100 : 0) : p.winRate;
    if ($("profile-matches")) $("profile-matches").textContent = String(matches);
    if ($("profile-wins")) $("profile-wins").textContent = String(wins);
    if ($("profile-winrate")) $("profile-winrate").textContent = formatRate(rate);
    if ($("profile-kda")) $("profile-kda").textContent = `${Number(p.kills) || 0} / ${Number(p.deaths) || 0} / ${Number(p.assists) || 0}`;
    if ($("account-summary")) $("account-summary").textContent = token ? `СЕТЕВОЙ АККАУНТ · УРОВЕНЬ ${p.level || 1}` : "ГОСТЕВОЙ ПРОФИЛЬ · ПРОГРЕСС НА ЭТОМ УСТРОЙСТВЕ";
    if ($("online-auth")) $("online-auth").hidden = !!token;
    if ($("online-logout")) $("online-logout").hidden = !token;
    if ($("account-medals")) renderMedals($("account-medals"), p.medals || []);
    updateHeader();
  }
  function setAuthMode(mode) {
    authMode = mode === "register" ? "register" : "login";
    const form = $("auth-form");
    const confirmation = $("auth-confirm-row");
    const submit = $("auth-submit");
    const toggle = $("auth-toggle");
    if (confirmation) confirmation.hidden = authMode !== "register";
    if (submit) submit.textContent = authMode === "register" ? "СОЗДАТЬ АККАУНТ" : "ВОЙТИ";
    if (toggle) toggle.textContent = authMode === "register" ? "Уже есть аккаунт? Войти" : "Создать новый аккаунт";
    if (form) form.reset();
    showAuthMessage(authMode === "register" ? "Имя 3–20 символов; пароль не короче 8." : "Войдите, чтобы синхронизировать статистику.", false);
  }
  async function refreshRemoteProfile() {
    if (!token) return;
    try {
      const data = await api("/api/profile", { method: "GET", headers: {} });
      remoteProfile = data.profile;
      renderProfilePanel();
    } catch (error) {
      if (error.status === 401) {
        setToken("");
        remoteProfile = null;
        showAuthMessage("Сеанс завершён. Войдите снова.", true);
      } else {
        showAuthMessage(`Профиль не синхронизирован: ${error.message}`, true);
      }
      renderProfilePanel();
    }
  }
  async function submitAuth(event) {
    event.preventDefault();
    const form = $("auth-form");
    const username = form.elements.username.value.trim();
    const password = form.elements.password.value;
    if (authMode === "register" && password !== form.elements.confirm.value) {
      showAuthMessage("Пароли не совпадают.", true);
      return;
    }
    const button = $("auth-submit");
    button.disabled = true;
    showAuthMessage("Связываемся с сервером профиля…", false);
    try {
      const data = await api(authMode === "register" ? "/api/register" : "/api/login", {
        method: "POST", body: JSON.stringify({ username, password }), headers: {}
      });
      setToken(data.token);
      remoteProfile = data.profile;
      showAuthMessage("Аккаунт подключён. Прогресс будет синхронизироваться с сервером.", false);
      renderProfilePanel();
      await refreshRemoteProfile();
    } catch (error) {
      showAuthMessage(error.message || "Не удалось подключиться.", true);
    } finally {
      button.disabled = false;
    }
  }
  async function logout() {
    try { await api("/api/logout", { method: "POST", body: "{}", headers: {} }); } catch (_) { /* local logout still succeeds */ }
    setToken("");
    remoteProfile = null;
    renderProfilePanel();
    showAuthMessage("Вы вышли. Локальная статистика на устройстве сохранена.", false);
  }
  async function recordMatch(result) {
    const win = !!result.victory;
    const kills = Math.max(0, Number(result.kills) || 0);
    const deaths = Math.max(0, Number(result.deaths) || 0);
    const assists = Math.max(0, Number(result.assists) || 0);
    const xpEarned = 100 + kills * 18 + assists * 8 + (win ? 120 : 0);
    localProfile.matches = (Number(localProfile.matches) || 0) + 1;
    localProfile.wins = (Number(localProfile.wins) || 0) + (win ? 1 : 0);
    localProfile.losses = (Number(localProfile.losses) || 0) + (win ? 0 : 1);
    localProfile.kills = (Number(localProfile.kills) || 0) + kills;
    localProfile.deaths = (Number(localProfile.deaths) || 0) + deaths;
    localProfile.assists = (Number(localProfile.assists) || 0) + assists;
    localProfile.xp = (Number(localProfile.xp) || 0) + xpEarned;
    localProfile.level = Math.min(100, Math.floor(localProfile.xp / 500) + 1);
    const localMedals = new Set(localProfile.medals || []);
    if (localProfile.matches === 1) localMedals.add("first_match");
    if (win && localProfile.wins === 1) localMedals.add("first_win");
    if (kills >= 5) localMedals.add("slayer");
    if (win && deaths <= 1) localMedals.add("unbroken");
    if (localProfile.matches >= 10) localMedals.add("veteran");
    if (localProfile.wins >= 5) localMedals.add("champion");
    localProfile.medals = [...localMedals];
    localProfile.winRate = localProfile.wins / Math.max(1, localProfile.matches) * 100;
    write(STORAGE.localProfile, localProfile);
    if (token) {
      try {
        const data = await api("/api/match", {
          method: "POST",
          body: JSON.stringify({
            result: win ? "win" : "loss", kills, deaths, assists,
            duration: Math.floor(Number(result.duration) || 0), gold: Math.max(0, Number(result.gold) || 0), hero: hero().id
          }), headers: {}
        });
        remoteProfile = data.profile;
      } catch (error) {
        showAuthMessage(`Матч записан локально. Сервер недоступен: ${error.message}`, true);
      }
    }
    updateHeader();
    renderProfilePanel();
  }
  function saveSettings() {
    write(STORAGE.settings, settings);
    document.dispatchEvent(new CustomEvent("rift-settings-change", { detail: Object.assign({}, settings) }));
  }
  function applySettingsToForm() {
    if ($("graphics-quality")) $("graphics-quality").value = settings.quality;
    if ($("music-volume")) $("music-volume").value = Math.round(settings.music * 100);
    if ($("effects-volume")) $("effects-volume").value = Math.round(settings.effects * 100);
    if ($("voice-enabled")) $("voice-enabled").checked = !!settings.voice;
    if ($("api-base")) $("api-base").value = settings.apiBase || "";
    if ($("music-value")) $("music-value").textContent = `${Math.round(settings.music * 100)}%`;
    if ($("effects-value")) $("effects-value").textContent = `${Math.round(settings.effects * 100)}%`;
  }
  function ensureAudio() {
    if (!audioContext) {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (AudioContextClass) audioContext = new AudioContextClass();
    }
    if (audioContext && audioContext.state === "suspended") audioContext.resume().catch(() => {});
    return audioContext;
  }
  function speak(line, heroId) {
    if (!settings.voice || !line || !window.speechSynthesis || !window.SpeechSynthesisUtterance) return;
    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(line);
      const speaker = HEROES[heroId || selectedHeroId] || hero();
      utterance.lang = "ru-RU";
      utterance.pitch = speaker.voice.pitch;
      utterance.rate = speaker.voice.rate;
      utterance.volume = Math.max(.05, settings.effects);
      window.speechSynthesis.speak(utterance);
    } catch (_) { /* voice is optional on older WebViews */ }
  }
  function playUiTone(frequency, duration, type) {
    if (settings.effects <= 0) return;
    const ctx = ensureAudio();
    if (!ctx) return;
    const oscillator = ctx.createOscillator();
    const gain = ctx.createGain();
    oscillator.type = type || "sine";
    oscillator.frequency.value = frequency || 440;
    gain.gain.setValueAtTime(.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(.045 * settings.effects, ctx.currentTime + .025);
    gain.gain.exponentialRampToValueAtTime(.0001, ctx.currentTime + (duration || .15));
    oscillator.connect(gain).connect(ctx.destination);
    oscillator.start();
    oscillator.stop(ctx.currentTime + (duration || .15) + .02);
  }
  function openAccount() {
    renderProfilePanel();
    openOverlay("account-overlay");
    if (!token) setAuthMode("login");
    else refreshRemoteProfile();
  }
  function openSettings() {
    applySettingsToForm();
    openOverlay("settings-overlay");
    testApi(true);
  }
  function openHeroes() {
    renderHeroPicker();
    openOverlay("heroes-overlay");
  }
  async function testApi(silent) {
    const status = $("api-status");
    if (!status) return;
    status.textContent = "Проверка сервера…";
    status.className = "api-status";
    try {
      const data = await api("/api/health", { method: "GET", headers: {} });
      status.textContent = `Сервер профиля доступен · API ${data.version || ""}`;
      status.classList.add("success");
    } catch (error) {
      status.textContent = error.message || "Сервер профиля не найден.";
      status.classList.add(silent ? "muted" : "error");
    }
  }

  function bindOverlay(overlayId, closeButtons) {
    const overlay = $(overlayId);
    if (!overlay) return;
    overlay.addEventListener("pointerdown", (event) => {
      if (event.target === overlay) closeOverlay(overlayId);
    });
    overlay.querySelectorAll(closeButtons).forEach((button) => button.addEventListener("click", () => closeOverlay(overlayId)));
  }

  setHeroText();
  renderHeroPicker();
  renderProfilePanel();
  applySettingsToForm();

  if ($("profile-open")) $("profile-open").addEventListener("click", openAccount);
  if ($("settings-open")) $("settings-open").addEventListener("click", openSettings);
  if ($("hero-info")) $("hero-info").addEventListener("click", () => { renderHeroPicker(); openOverlay("heroes-overlay"); });
  if ($("auth-form")) $("auth-form").addEventListener("submit", submitAuth);
  if ($("auth-toggle")) $("auth-toggle").addEventListener("click", () => setAuthMode(authMode === "login" ? "register" : "login"));
  if ($("online-logout")) $("online-logout").addEventListener("click", logout);
  if ($("api-test")) $("api-test").addEventListener("click", () => testApi(false));
  if ($("graphics-quality")) $("graphics-quality").addEventListener("change", (event) => { settings.quality = event.target.value; saveSettings(); });
  if ($("music-volume")) $("music-volume").addEventListener("input", (event) => {
    settings.music = Number(event.target.value) / 100;
    if ($("music-value")) $("music-value").textContent = `${event.target.value}%`;
    saveSettings();
  });
  if ($("effects-volume")) $("effects-volume").addEventListener("input", (event) => {
    settings.effects = Number(event.target.value) / 100;
    if ($("effects-value")) $("effects-value").textContent = `${event.target.value}%`;
    saveSettings();
  });
  if ($("voice-enabled")) $("voice-enabled").addEventListener("change", (event) => { settings.voice = event.target.checked; saveSettings(); });
  if ($("api-base")) $("api-base").addEventListener("change", (event) => {
    settings.apiBase = event.target.value.trim().replace(/\/+$/, "");
    saveSettings();
    testApi(true);
  });
  document.querySelectorAll("[data-overlay-close]").forEach((button) => button.addEventListener("click", () => closeOverlay(button.dataset.overlayClose)));
  bindOverlay("heroes-overlay", "[data-overlay-close='heroes-overlay']");
  bindOverlay("account-overlay", "[data-overlay-close='account-overlay']");
  bindOverlay("settings-overlay", "[data-overlay-close='settings-overlay']");
  window.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    for (const id of ["heroes-overlay", "account-overlay", "settings-overlay"]) {
      if ($(id) && !$(id).hidden) { closeOverlay(id); return; }
    }
  });
  if (token) refreshRemoteProfile();

  window.RiftMeta = {
    HEROES,
    MEDALS,
    get hero() { return hero(); },
    get selectedHeroId() { return selectedHeroId; },
    get selectedSkinId() { return selectedSkinId; },
    get settings() { return Object.assign({}, settings); },
    get profile() { return profile(); },
    selectHero,
    selectSkin,
    recordMatch,
    speak,
    playUiTone,
    ensureAudio,
    openSettings,
    openAccount,
    openHeroes,
    getSkinPalette(id, skinId) {
      const item = HEROES[id] || HEROES.elaris;
      const selected = item.skins.find((entry) => entry.id === skinId) || item.skins[0];
      return Object.assign({}, item.palette, selected.colors || {});
    }
  };
})();
