using System;
using System.Collections.Generic;
using UnityEngine;

[Serializable]
public struct HeroAbilityData
{
    public string key;
    public string displayName;
    public float cooldown;
    public float manaCost;
    public float damage;
    public float radius;
    public float range;
}

[CreateAssetMenu(menuName = "Rift Arena/Hero", fileName = "HeroDefinition")]
public sealed class HeroDefinition : ScriptableObject
{
    [Header("Identity")]
    public string heroId;
    public string displayName;
    public string epithet;
    public string role;
    public string difficulty;
    public string[] skinNames;
    [TextArea] public string intro;

    [Header("Combat balance")]
    public float maxHealth = 800f;
    public float maxMana = 480f;
    public float moveSpeed = 3.6f;
    public float basicDamage = 38f;
    public float attackRate = 1f;
    public float attackRange = 3.5f;
    public HeroAbilityData[] abilities = Array.Empty<HeroAbilityData>();

    [Header("Original procedural look")]
    public Color primary = new Color(.16f, .42f, .47f);
    public Color armor = new Color(.32f, .66f, .68f);
    public Color accent = new Color(.84f, .71f, .47f);
    public Color glow = new Color(.43f, .9f, .84f);
    public float voicePitch = 1f;

    public static class Catalog
    {
        private static List<HeroDefinition> definitions;

        public static IReadOnlyList<HeroDefinition> All
        {
            get
            {
                if (definitions == null) definitions = CreatePrototypeRoster();
                return definitions;
            }
        }

        public static HeroDefinition Get(string id)
        {
            foreach (HeroDefinition hero in All)
                if (string.Equals(hero.heroId, id, StringComparison.OrdinalIgnoreCase)) return hero;
            return All[0];
        }

        private static List<HeroDefinition> CreatePrototypeRoster()
        {
            var list = new List<HeroDefinition>();
            list.Add(Create(
                "elaris", "Эларис", "Хранительница рассвета", "МАГ", "СРЕДНЯЯ",
                "Маг дальнего боя: удерживает линию сияющими осколками.",
                780, 500, 3.65f, 39, .78f, 4.0f,
                new Color(.15f, .42f, .48f), new Color(.31f, .66f, .68f), new Color(.84f, .71f, .47f), new Color(.45f, .92f, .86f), 1.12f,
                new[] { "Рассветная стража", "Лунное затмение" },
                Ability("Q", "Рассветный осколок", 6, 55, 135, 2.35f, 6),
                Ability("W", "Скачок по лучу", 10, 70, 105, 2.1f, 3.6f),
                Ability("E", "Солнечная сфера", 8, 70, 175, 0, 10),
                Ability("R", "Сияние", 28, 115, 245, 6.3f, 7)));
            list.Add(Create(
                "raen", "Раэн", "Клинок тихой бури", "УБИЙЦА", "ВЫСОКАЯ",
                "Быстрый боец: входит в схватку, наносит серию ударов и уходит из-под ответа.",
                680, 420, 4.35f, 47, .68f, 3.2f,
                new Color(.30f, .22f, .40f), new Color(.51f, .39f, .60f), new Color(.50f, .88f, .81f), new Color(.51f, .94f, .86f), .88f,
                new[] { "Буревестник", "Пепельный странник" },
                Ability("Q", "Двойной разрез", 5, 42, 160, 2.35f, 5.5f),
                Ability("W", "Шаг сквозь тень", 8, 48, 125, 2.1f, 4),
                Ability("E", "Метательный клинок", 9, 58, 190, 0, 10),
                Ability("R", "Буря клинков", 25, 100, 230, 6.3f, 7)));
            list.Add(Create(
                "varkor", "Варкор", "Страж каменного сердца", "ТАНК", "НИЗКАЯ",
                "Тяжёлый защитник: выдерживает давление и открывает команде путь к объектам.",
                1080, 430, 3.05f, 34, .96f, 3.5f,
                new Color(.32f, .38f, .31f), new Color(.53f, .57f, .42f), new Color(.85f, .72f, .43f), new Color(.85f, .72f, .43f), .72f,
                new[] { "Сердце гранита", "Глубинный обсидиан" },
                Ability("Q", "Раскол земли", 7, 50, 155, 2.45f, 5.5f),
                Ability("W", "Таран стража", 12, 64, 118, 2.1f, 4),
                Ability("E", "Каменный заслон", 10, 62, 155, 0, 9),
                Ability("R", "Гнев разлома", 32, 125, 260, 6.3f, 7)));
            return list;
        }

        private static HeroAbilityData Ability(string key, string name, float cooldown, float cost, float damage, float radius, float range)
        {
            return new HeroAbilityData { key = key, displayName = name, cooldown = cooldown, manaCost = cost, damage = damage, radius = radius, range = range };
        }

        private static HeroDefinition Create(string id, string name, string epithet, string role, string difficulty, string intro,
            float hp, float mana, float speed, float damage, float attackRate, float attackRange,
            Color primary, Color armor, Color accent, Color glow, float pitch, string[] skins,
            HeroAbilityData q, HeroAbilityData w, HeroAbilityData e, HeroAbilityData r)
        {
            HeroDefinition hero = ScriptableObject.CreateInstance<HeroDefinition>();
            hero.hideFlags = HideFlags.DontSave;
            hero.heroId = id;
            hero.displayName = name;
            hero.epithet = epithet;
            hero.role = role;
            hero.difficulty = difficulty;
            hero.intro = intro;
            hero.maxHealth = hp;
            hero.maxMana = mana;
            hero.moveSpeed = speed;
            hero.basicDamage = damage;
            hero.attackRate = attackRate;
            hero.attackRange = attackRange;
            hero.primary = primary;
            hero.armor = armor;
            hero.accent = accent;
            hero.glow = glow;
            hero.voicePitch = pitch;
            hero.skinNames = skins;
            hero.abilities = new[] { q, w, e, r };
            return hero;
        }
    }
}
