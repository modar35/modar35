const CATEGORIES = [
  { id: "auto", name: "Авто", emoji: "🚗" },
  { id: "realty", name: "Недвижимость", emoji: "🏠" },
  { id: "job", name: "Работа", emoji: "💼" },
  { id: "services", name: "Услуги", emoji: "🛠" },
  { id: "electronics", name: "Электроника", emoji: "📱" },
  { id: "home", name: "Для дома", emoji: "🛋" },
  { id: "fashion", name: "Одежда", emoji: "👕" },
  { id: "hobby", name: "Хобби", emoji: "🎸" }
];

const CITIES = ["Москва", "Санкт-Петербург", "Казань", "Новосибирск", "Екатеринбург", "Краснодар"];

const SEED = [
  {
    id: "1",
    title: "Toyota Camry 2020, 45 000 км",
    price: 2450000,
    cat: "auto",
    city: "Москва",
    image: "https://images.unsplash.com/photo-1621007947382-bb3c0433a0b0?auto=format&fit=crop&w=1200&q=80",
    desc: "Один владелец, сервисная книжка, полный комплект. Кожа, камера, подогревы. Без ДТП.",
    seller: "Алексей",
    phone: "+7 900 111-22-33",
    date: "Сегодня"
  },
  {
    id: "2",
    title: "2-к квартира у метро, 54 м²",
    price: 12500000,
    cat: "realty",
    city: "Санкт-Петербург",
    image: "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=1200&q=80",
    desc: "Светлая квартира, кухня 12 м², балкон. Чистый подъезд, охрана. Торг уместен.",
    seller: "Мария",
    phone: "+7 911 555-01-02",
    date: "Вчера"
  },
  {
    id: "3",
    title: "iPhone 15 Pro 256 ГБ",
    price: 89900,
    cat: "electronics",
    city: "Казань",
    image: "https://images.unsplash.com/photo-1695048133142-1a20484d2569?auto=format&fit=crop&w=1200&q=80",
    desc: "Состояние отличное, полный комплект, чехол и стекло в подарок. Гарантия Apple.",
    seller: "Игорь",
    phone: "+7 917 222-33-44",
    date: "2 дня назад"
  },
  {
    id: "4",
    title: "Диван угловой, как новый",
    price: 24900,
    cat: "home",
    city: "Москва",
    image: "https://images.unsplash.com/photo-1555041469-a586c61ea9bc?auto=format&fit=crop&w=1200&q=80",
    desc: "Ткань рогожка, ящик для белья. Самовывоз из САО. Можно помочь с доставкой.",
    seller: "Ольга",
    phone: "+7 926 100-20-30",
    date: "Сегодня"
  },
  {
    id: "5",
    title: "Велосипед горный Trek",
    price: 42000,
    cat: "hobby",
    city: "Екатеринбург",
    image: "https://images.unsplash.com/photo-1485965120184-e220f545d2e4?auto=format&fit=crop&w=1200&q=80",
    desc: "Рама M, 29 дюймов, гидравлика. Ездили мало, хранился в квартире.",
    seller: "Павел",
    phone: "+7 343 700-11-22",
    date: "3 дня назад"
  },
  {
    id: "6",
    title: "Куртка зимняя North Face",
    price: 8900,
    cat: "fashion",
    city: "Новосибирск",
    image: "https://images.unsplash.com/photo-1539533018447-63fcce2678e3?auto=format&fit=crop&w=1200&q=80",
    desc: "Размер L, пух 700. Носили один сезон. Без дефектов.",
    seller: "Анна",
    phone: "+7 913 444-55-66",
    date: "Вчера"
  },
  {
    id: "7",
    title: "Ремонт квартир под ключ",
    price: 0,
    cat: "services",
    city: "Краснодар",
    image: "https://images.unsplash.com/photo-1503387762-592deb58ef4e?auto=format&fit=crop&w=1200&q=80",
    desc: "Бригада с опытом 12 лет. Смета бесплатно. Гарантия 3 года. Черновая и чистовая отделка.",
    seller: "Сергей",
    phone: "+7 918 777-88-99",
    date: "Сегодня"
  },
  {
    id: "8",
    title: "Менеджер маркетплейсов, удалёнка",
    price: 90000,
    cat: "job",
    city: "Москва",
    image: "https://images.unsplash.com/photo-1521737604893-d14cc237f11d?auto=format&fit=crop&w=1200&q=80",
    desc: "Ведение кабинета Wildberries/Ozon. Опыт от 1 года. Оформление по ТК, бонусы.",
    seller: "HR Наталья",
    phone: "+7 495 000-12-12",
    date: "Сегодня"
  }
];
