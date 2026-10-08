using System;
using UnityEngine;

public enum RiftTeam { Blue, Red, Neutral }
public enum RiftUnitKind { Hero, Minion, Monster, Tower, Core }

public sealed class UnitActor : MonoBehaviour
{
    public string displayName;
    public RiftTeam team;
    public RiftUnitKind kind;
    public HeroDefinition heroDefinition;
    public float maxHealth;
    public float health;
    public float maxMana;
    public float mana;
    public float moveSpeed;
    public float basicDamage;
    public float attackRange;
    public float attackRate;
    public int level = 1;
    public int kills;
    public int deaths;
    public bool IsAlive { get; private set; } = true;
    public float HealthRatio => maxHealth <= 0 ? 0 : health / maxHealth;
    public float ManaRatio => maxMana <= 0 ? 0 : mana / maxMana;

    public event Action<UnitActor, UnitActor> Died;

    public void Configure(string name, RiftTeam owner, RiftUnitKind unitKind, Vector3 position,
        float hp, float speed, float damage, float range, float rate, HeroDefinition definition = null, float manaValue = 0)
    {
        displayName = name;
        team = owner;
        kind = unitKind;
        transform.position = position;
        heroDefinition = definition;
        maxHealth = Mathf.Max(1, hp);
        health = maxHealth;
        maxMana = Mathf.Max(0, manaValue);
        mana = maxMana;
        moveSpeed = Mathf.Max(.1f, speed);
        basicDamage = Mathf.Max(0, damage);
        attackRange = Mathf.Max(.5f, range);
        attackRate = Mathf.Max(.15f, rate);
        level = 1;
        kills = 0;
        deaths = 0;
        experience = 0;
        nextLevelExperience = 300;
        IsAlive = true;
    }

    public void Move(Vector3 direction, float deltaTime)
    {
        if (!IsAlive || direction.sqrMagnitude < .0001f) return;
        direction.y = 0;
        direction.Normalize();
        transform.position += direction * moveSpeed * deltaTime;
        transform.position = new Vector3(Mathf.Clamp(transform.position.x, -13.5f, 13.5f), 0, Mathf.Clamp(transform.position.z, -13.5f, 13.5f));
        if (direction.sqrMagnitude > .01f) transform.rotation = Quaternion.LookRotation(direction, Vector3.up);
    }

    public void Face(Vector3 target)
    {
        Vector3 direction = target - transform.position;
        direction.y = 0;
        if (direction.sqrMagnitude > .001f) transform.rotation = Quaternion.LookRotation(direction.normalized, Vector3.up);
    }

    public void ReceiveDamage(float damage, UnitActor source)
    {
        if (!IsAlive || damage <= 0) return;
        health = Mathf.Max(0, health - damage);
        if (health <= 0)
        {
            IsAlive = false;
            Died?.Invoke(this, source);
        }
    }

    public void Restore(float healthAmount, float manaAmount)
    {
        health = Mathf.Min(maxHealth, health + Mathf.Max(0, healthAmount));
        mana = Mathf.Min(maxMana, mana + Mathf.Max(0, manaAmount));
    }

    public void Respawn(Vector3 position)
    {
        transform.position = position;
        health = maxHealth;
        mana = maxMana;
        IsAlive = true;
    }

    public void AddExperience(int amount)
    {
        if (amount <= 0 || kind != RiftUnitKind.Hero) return;
        experience += amount;
        while (level < 12 && experience >= nextLevelExperience)
        {
            experience -= nextLevelExperience;
            level++;
            nextLevelExperience = Mathf.CeilToInt(nextLevelExperience * 1.16f);
            float growth = heroDefinition != null && heroDefinition.heroId == "varkor" ? 105 : 70;
            maxHealth += growth;
            health = Mathf.Min(maxHealth, health + growth);
            maxMana += 35;
            mana = Mathf.Min(maxMana, mana + 35);
            basicDamage += heroDefinition != null && heroDefinition.heroId == "raen" ? 5 : 4;
        }
    }

    public int Experience => experience;
    public int ExperienceForNextLevel => nextLevelExperience;
    private int experience;
    private int nextLevelExperience = 300;
}
