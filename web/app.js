const $ = (sel, el = document) => el.querySelector(sel);
const app = $("#app");

const store = {
  get ads() {
    const extra = JSON.parse(localStorage.getItem("bazar_ads") || "[]");
    return [...extra, ...SEED];
  },
  add(ad) {
    const extra = JSON.parse(localStorage.getItem("bazar_ads") || "[]");
    extra.unshift(ad);
    localStorage.setItem("bazar_ads", JSON.stringify(extra));
  },
  favs: JSON.parse(localStorage.getItem("bazar_favs") || "[]"),
  toggleFav(id) {
    this.favs = this.favs.includes(id) ? this.favs.filter((x) => x !== id) : [...this.favs, id];
    localStorage.setItem("bazar_favs", JSON.stringify(this.favs));
  }
};

let state = { view: "home", q: "", cat: "", city: "", sort: "new", adId: null };

function money(n) {
  if (!n) return "Цена договорная";
  return n.toLocaleString("ru-RU") + " ₽";
}

function go(patch) {
  state = { ...state, ...patch };
  render();
  window.scrollTo(0, 0);
}

function header() {
  return `
    <header class="topbar">
      <div class="topbar-inner">
        <a class="logo" href="#" data-go="home">Базар<span>.</span></a>
        <form class="search" id="searchForm">
          <input name="q" placeholder="Поиск по объявлениям" value="${state.q}" />
          <button>Найти</button>
        </form>
        <div class="nav-actions">
          <button class="icon-btn" data-go="favs"><span class="ic">♡</span>Избранное</button>
          <button class="cta" data-go="post">+ Разместить</button>
        </div>
      </div>
    </header>
  `;
}

function home() {
  let ads = store.ads.filter((a) => {
    const q = state.q.toLowerCase();
    const okQ = !q || a.title.toLowerCase().includes(q) || a.desc.toLowerCase().includes(q);
    const okC = !state.cat || a.cat === state.cat;
    const okCity = !state.city || a.city === state.city;
    return okQ && okC && okCity;
  });
  if (state.sort === "cheap") ads = ads.slice().sort((a, b) => a.price - b.price);
  if (state.sort === "exp") ads = ads.slice().sort((a, b) => b.price - a.price);

  const catName = CATEGORIES.find((c) => c.id === state.cat)?.name;

  return `
    ${header()}
    <main class="wrap">
      ${!state.q && !state.cat ? `
      <section class="hero">
        <div>
          <h1>Бесплатные объявления рядом с вами</h1>
          <p>Как Авито, только без комиссии: покупайте, продавайте и находите работу.</p>
          <div class="hero-stats">
            <div><b>${store.ads.length}+</b><span>объявлений</span></div>
            <div><b>0 ₽</b><span>за размещение</span></div>
            <div><b>8</b><span>категорий</span></div>
          </div>
        </div>
        <div class="hero-card">
          <small>Совет дня</small>
          <p style="margin-top:8px">Добавьте фото и цену — объявление находят в 4 раза чаще.</p>
        </div>
      </section>` : ""}
      <div class="cats">
        ${CATEGORIES.map((c) => `
          <div class="cat" data-cat="${c.id}">
            <div class="emoji">${c.emoji}</div>
            <div class="name">${c.name}</div>
          </div>`).join("")}
      </div>
      <div class="toolbar">
        <h2>${catName || (state.q ? `Поиск: «${state.q}»` : "Свежие объявления")}</h2>
        <div class="filters">
          <select id="city">
            <option value="">Все города</option>
            ${CITIES.map((c) => `<option ${state.city === c ? "selected" : ""}>${c}</option>`).join("")}
          </select>
          <select id="sort">
            <option value="new" ${state.sort === "new" ? "selected" : ""}>Сначала новые</option>
            <option value="cheap" ${state.sort === "cheap" ? "selected" : ""}>Дешевле</option>
            <option value="exp" ${state.sort === "exp" ? "selected" : ""}>Дороже</option>
          </select>
        </div>
      </div>
      ${ads.length ? `<div class="grid">${ads.map(card).join("")}</div>` : `<div class="empty">Ничего не найдено. Измените фильтры или разместите объявление.</div>`}
    </main>
    <footer>Базар — бесплатная доска объявлений. Прототип без бэкенда, данные в браузере.</footer>
  `;
}

function card(a) {
  const heart = store.favs.includes(a.id) ? "♥" : "♡";
  return `
    <article class="card" data-ad="${a.id}">
      <div class="thumb">
        <img src="${a.image}" alt="" />
        <span class="badge">${CATEGORIES.find((c) => c.id === a.cat)?.name || ""}</span>
        <button class="fav" data-fav="${a.id}">${heart}</button>
      </div>
      <div class="card-body">
        <div class="price">${money(a.price)}</div>
        <div class="title">${a.title}</div>
        <div class="meta"><span>${a.city}</span><span>${a.date}</span></div>
      </div>
    </article>
  `;
}

function detail() {
  const a = store.ads.find((x) => x.id === state.adId);
  if (!a) return home();
  return `
    ${header()}
    <main class="wrap">
      <button class="back" data-go="home">← Назад к ленте</button>
      <div class="detail">
        <div class="gallery"><img src="${a.image}" alt="" /></div>
        <aside class="side">
          <div class="price">${money(a.price)}</div>
          <h2 style="margin:8px 0 12px">${a.title}</h2>
          <div class="muted">${a.city} · ${a.date}</div>
          <div class="seller">
            <div class="avatar">${a.seller[0]}</div>
            <div><b>${a.seller}</b><div class="muted">Частное лицо</div></div>
          </div>
          <div class="phone">${a.phone}</div>
          <button class="cta" style="width:100%" data-fav="${a.id}">${store.favs.includes(a.id) ? "В избранном" : "В избранное"}</button>
        </aside>
      </div>
      <div class="desc"><h3>Описание</h3><p style="margin-top:10px">${a.desc}</p></div>
    </main>
  `;
}

function favs() {
  const ads = store.ads.filter((a) => store.favs.includes(a.id));
  return `
    ${header()}
    <main class="wrap">
      <h2>Избранное</h2>
      ${ads.length ? `<div class="grid" style="margin-top:16px">${ads.map(card).join("")}</div>` : `<div class="empty">Пока пусто. Нажмите ♡ на карточке.</div>`}
    </main>
  `;
}

function post() {
  return `
    ${header()}
    <main class="wrap">
      <form class="form" id="postForm">
        <h2>Новое объявление — бесплатно</h2>
        <p class="muted">Заполните форму. Объявление сохранится в этом браузере.</p>
        <label>Заголовок</label>
        <input name="title" required maxlength="80" placeholder="Например, Велосипед Trek 29" />
        <div class="row2">
          <div>
            <label>Категория</label>
            <select name="cat" required>
              ${CATEGORIES.map((c) => `<option value="${c.id}">${c.name}</option>`).join("")}
            </select>
          </div>
          <div>
            <label>Город</label>
            <select name="city">${CITIES.map((c) => `<option>${c}</option>`).join("")}</select>
          </div>
        </div>
        <div class="row2">
          <div>
            <label>Цена, ₽ (0 = договорная)</label>
            <input name="price" type="number" min="0" value="0" />
          </div>
          <div>
            <label>Телефон</label>
            <input name="phone" required placeholder="+7 900 000-00-00" />
          </div>
        </div>
        <label>Ваше имя</label>
        <input name="seller" required />
        <label>Ссылка на фото</label>
        <input name="image" placeholder="https://..." />
        <label>Описание</label>
        <textarea name="desc" required></textarea>
        <button class="cta" style="margin-top:16px;width:100%">Опубликовать</button>
      </form>
    </main>
  `;
}

function render() {
  if (state.view === "detail") app.innerHTML = detail();
  else if (state.view === "favs") app.innerHTML = favs();
  else if (state.view === "post") app.innerHTML = post();
  else app.innerHTML = home();
  bind();
}

function bind() {
  app.querySelectorAll("[data-go]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.preventDefault();
      go({ view: el.dataset.go, adId: null });
    });
  });
  const sf = $("#searchForm");
  if (sf) sf.addEventListener("submit", (e) => {
    e.preventDefault();
    go({ view: "home", q: sf.q.value.trim() });
  });
  app.querySelectorAll("[data-cat]").forEach((el) => {
    el.addEventListener("click", () => go({ view: "home", cat: el.dataset.cat }));
  });
  app.querySelectorAll("[data-ad]").forEach((el) => {
    el.addEventListener("click", (e) => {
      if (e.target.closest("[data-fav]")) return;
      go({ view: "detail", adId: el.dataset.ad });
    });
  });
  app.querySelectorAll("[data-fav]").forEach((el) => {
    el.addEventListener("click", (e) => {
      e.stopPropagation();
      store.toggleFav(el.dataset.fav);
      render();
    });
  });
  const city = $("#city");
  if (city) city.addEventListener("change", () => go({ city: city.value }));
  const sort = $("#sort");
  if (sort) sort.addEventListener("change", () => go({ sort: sort.value }));
  const pf = $("#postForm");
  if (pf) pf.addEventListener("submit", (e) => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(pf));
    store.add({
      id: String(Date.now()),
      title: f.title,
      price: Number(f.price) || 0,
      cat: f.cat,
      city: f.city,
      image: f.image || "https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=1200&q=80",
      desc: f.desc,
      seller: f.seller,
      phone: f.phone,
      date: "Сегодня"
    });
    go({ view: "home", cat: "", q: "" });
  });
}

render();
