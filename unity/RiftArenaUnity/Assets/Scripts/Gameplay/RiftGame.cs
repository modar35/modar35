using System.Collections;
using System.Collections.Generic;
using UnityEngine;

public sealed class RiftGame : MonoBehaviour
{
    public enum MatchPhase { Lobby, Loading, Playing, Paused, Result }

    public static RiftGame Instance { get; private set; }
    public MatchPhase Phase { get; private set; } = MatchPhase.Lobby;
    public HeroDefinition SelectedHero { get; private set; }
    public int SelectedSkin { get; private set; }
    public UnitActor Player { get; private set; }
    public UnitActor EnemyHero => enemyHero;
    public int Gold { get; private set; } = 245;
    public int Kills { get; private set; }
    public int Deaths { get; private set; }
    public int Assists { get; private set; }
    public float MatchTime { get; private set; }
    public string LastStatus { get; private set; } = string.Empty;
    public ProfileApiClient Api { get; private set; }
    public RiftProfileData Profile => RiftProfileStore.Load();

    private readonly List<UnitActor> minions = new List<UnitActor>();
    private readonly List<UnitActor> structures = new List<UnitActor>();
    private readonly Dictionary<UnitActor, float> attackTimers = new Dictionary<UnitActor, float>();
    private readonly Dictionary<UnitActor, float> respawnTimers = new Dictionary<UnitActor, float>();
    private readonly float[] abilityCooldowns = new float[4];
    private Transform worldRoot;
    private Transform actorRoot;
    private Camera gameCamera;
    private Light keyLight;
    private RiftUI ui;
    private UnitActor enemyHero;
    private UnitActor monster;
    private UnitActor blueTower;
    private UnitActor redTower;
    private UnitActor blueCore;
    private UnitActor redCore;
    private GameObject showcaseHero;
    private Vector2 touchMove;
    private bool touchAttackHeld;
    private float waveTimer;
    private float uiTimer;
    private float jungleBuffUntil;
    private float jungleBuffBonus;
    private int waveNumber;

    private static readonly string[] LoadingStages =
    {
        "Загружаем модели героя…",
        "Подготавливаем 3D-карту…",
        "Расставляем башни и кристаллы…",
        "Проверяем умения и баланс…",
        "Арена готова!"
    };

    private void Awake()
    {
        if (Instance != null && Instance != this)
        {
            Destroy(gameObject);
            return;
        }
        Instance = this;
        DontDestroyOnLoad(gameObject);
        Screen.orientation = ScreenOrientation.LandscapeLeft;
        Screen.fullScreen = true;
        Application.targetFrameRate = 60;
        RiftSettingsStore.Apply();
        Api = gameObject.AddComponent<ProfileApiClient>();
        CreateCameraAndLight();
        gameObject.AddComponent<RiftAudioDirector>();
        worldRoot = new GameObject("Rift World").transform;
        worldRoot.SetParent(transform, false);
        ArenaBuilder.BuildWorld(worldRoot);
        actorRoot = new GameObject("Actors").transform;
        actorRoot.SetParent(transform, false);
        CreateStructures();
        SelectedHero = HeroDefinition.Catalog.All[0];
        RefreshShowcaseHero();
        ui = gameObject.AddComponent<RiftUI>();
        ui.Initialize(this);
    }

    private void Start()
    {
        if (Api != null && Api.IsConnected && !string.IsNullOrEmpty(Api.BaseUrl))
            Api.FetchProfile((success, message, profile) =>
            {
                LastStatus = message;
                if (success && profile != null) RiftProfileStore.Save(profile);
            });
    }

    private void CreateCameraAndLight()
    {
        GameObject cameraObject = new GameObject("Rift Main Camera");
        cameraObject.tag = "MainCamera";
        cameraObject.transform.SetParent(transform, false);
        gameCamera = cameraObject.AddComponent<Camera>();
        cameraObject.AddComponent<AudioListener>();
        gameCamera.clearFlags = CameraClearFlags.SolidColor;
        gameCamera.backgroundColor = new Color(.035f, .075f, .07f);
        gameCamera.orthographic = true;
        gameCamera.orthographicSize = 12.8f;
        gameCamera.nearClipPlane = .1f;
        gameCamera.farClipPlane = 100f;
        gameCamera.transform.position = new Vector3(-1.2f, 20.5f, 18.5f);
        gameCamera.transform.LookAt(new Vector3(-1.2f, 0, -.25f));
        gameCamera.allowHDR = true;
        gameCamera.allowMSAA = QualitySettings.antiAliasing > 0;

        GameObject lightObject = new GameObject("Dusk Directional Light");
        lightObject.transform.SetParent(transform, false);
        lightObject.transform.rotation = Quaternion.Euler(48, -28, 0);
        keyLight = lightObject.AddComponent<Light>();
        Light sunlight = keyLight;
        sunlight.type = LightType.Directional;
        sunlight.color = new Color(1f, .86f, .66f);
        sunlight.intensity = 1.2f;
        sunlight.shadows = RiftSettingsStore.Quality == 0 ? LightShadows.None : LightShadows.Soft;
        RenderSettings.ambientLight = new Color(.38f, .46f, .41f);
        RenderSettings.fog = true;
        RenderSettings.fogColor = new Color(.09f, .16f, .14f);
        RenderSettings.fogMode = FogMode.ExponentialSquared;
        RenderSettings.fogDensity = .012f;
    }

    private void CreateStructures()
    {
        blueCore = CreateStructure("Союзное ядро", RiftTeam.Blue, RiftUnitKind.Core, new Vector3(-12, 0, -12), new Color(.3f, .78f, .86f), 1800);
        blueTower = CreateStructure("Союзная башня", RiftTeam.Blue, RiftUnitKind.Tower, new Vector3(-5.4f, 0, -5.4f), new Color(.35f, .78f, .87f), 980);
        redTower = CreateStructure("Вражеская башня", RiftTeam.Red, RiftUnitKind.Tower, new Vector3(5.4f, 0, 5.4f), new Color(.91f, .4f, .34f), 980);
        redCore = CreateStructure("Вражеское ядро", RiftTeam.Red, RiftUnitKind.Core, new Vector3(12, 0, 12), new Color(.91f, .4f, .34f), 1800);
    }

    private UnitActor CreateStructure(string name, RiftTeam team, RiftUnitKind kind, Vector3 position, Color color, float health)
    {
        UnitActor actor = ArenaBuilder.CreateUnit(worldRoot, name, team, kind, position, color, new Color(.75f, .66f, .45f));
        actor.Configure(name, team, kind, position, health, 0, 0, 7.2f, 1.45f);
        actor.Died += OnUnitDied;
        structures.Add(actor);
        return actor;
    }

    private void Update()
    {
        if (Phase != MatchPhase.Playing) return;
        if (Input.GetKeyDown(KeyCode.Escape)) { PauseMatch(); return; }
        float dt = Mathf.Min(Time.deltaTime, .08f);
        MatchTime += dt;
        UpdateTimers(dt);
        UpdatePlayer(dt);
        UpdateEnemyHero(dt);
        UpdateMinions(dt);
        UpdateNeutralMonster(dt);
        UpdateTowers(dt);
        UpdateRespawns();
        waveTimer += dt;
        if (waveTimer >= 19f)
        {
            waveTimer = 0;
            SpawnWave();
        }
        UpdateCamera(dt);
        uiTimer += dt;
        if (uiTimer >= .15f)
        {
            uiTimer = 0;
            if (ui != null) ui.RefreshHud();
        }
    }

    public void SelectHero(int direction)
    {
        IReadOnlyList<HeroDefinition> roster = HeroDefinition.Catalog.All;
        int index = 0;
        for (int i = 0; i < roster.Count; i++) if (roster[i].heroId == SelectedHero.heroId) index = i;
        index = (index + direction + roster.Count) % roster.Count;
        SelectedHero = roster[index];
        SelectedSkin = 0;
        RefreshShowcaseHero();
        if (ui != null) ui.RefreshHeroSelection();
    }

    public void SelectSkin(int direction)
    {
        int skinCount = Mathf.Max(1, SelectedHero.skinNames == null ? 0 : SelectedHero.skinNames.Length);
        SelectedSkin = (SelectedSkin + direction + skinCount) % skinCount;
        RefreshShowcaseHero();
        if (ui != null) ui.RefreshHeroSelection();
    }

    private void RefreshShowcaseHero()
    {
        if (showcaseHero != null) Destroy(showcaseHero);
        Color main = SelectedHero.primary;
        Color accent = SelectedHero.accent;
        UnitActor actor = ArenaBuilder.CreateUnit(worldRoot, "Selected Champion", RiftTeam.Blue, RiftUnitKind.Hero,
            new Vector3(2.45f, 0, -.2f), main, accent, SelectedHero, SelectedSkin);
        actor.Configure(SelectedHero.displayName, RiftTeam.Blue, RiftUnitKind.Hero, new Vector3(2.45f, 0, -.2f),
            SelectedHero.maxHealth, SelectedHero.moveSpeed, SelectedHero.basicDamage, SelectedHero.attackRange,
            SelectedHero.attackRate, SelectedHero, SelectedHero.maxMana);
        showcaseHero = actor.gameObject;
        actor.Died += OnUnitDied;
        actor.transform.rotation = Quaternion.Euler(0, -25, 0);
    }

    public void StartMatch()
    {
        if (Phase == MatchPhase.Playing || Phase == MatchPhase.Loading) return;
        Time.timeScale = 1f;
        RiftAudioDirector.SetPaused(false);
        StartCoroutine(PrepareMatch());
    }

    private IEnumerator PrepareMatch()
    {
        Phase = MatchPhase.Loading;
        if (ui != null) ui.ShowLoading(0, LoadingStages[0]);
        for (int i = 0; i < LoadingStages.Length; i++)
        {
            if (ui != null) ui.ShowLoading((i + 1f) / LoadingStages.Length, LoadingStages[i]);
            yield return new WaitForSecondsRealtime(.24f);
        }
        BeginMatch();
    }

    private void BeginMatch()
    {
        ClearActors();
        ResetStructure(blueCore, 1800);
        ResetStructure(blueTower, 980);
        ResetStructure(redTower, 980);
        ResetStructure(redCore, 1800);
        Gold = 245;
        Kills = 0;
        Deaths = 0;
        Assists = 0;
        MatchTime = 0;
        waveTimer = 0;
        waveNumber = 1;
        jungleBuffUntil = 0;
        jungleBuffBonus = 0;
        for (int i = 0; i < abilityCooldowns.Length; i++) abilityCooldowns[i] = 0;
        touchMove = Vector2.zero;
        touchAttackHeld = false;

        if (showcaseHero != null) showcaseHero.SetActive(false);
        Player = CreateHero(SelectedHero, RiftTeam.Blue, new Vector3(-4.1f, 0, -4.1f), true);
        HeroDefinition enemyDefinition = HeroDefinition.Catalog.Get(SelectedHero.heroId == "elaris" ? "varkor" : SelectedHero.heroId == "raen" ? "elaris" : "raen");
        enemyHero = CreateHero(enemyDefinition, RiftTeam.Red, new Vector3(3.4f, 0, 3.4f), false);
        monster = ArenaBuilder.CreateUnit(actorRoot, "Нейтральный хранитель", RiftTeam.Neutral, RiftUnitKind.Monster,
            new Vector3(1.8f, 0, -4.5f), new Color(.48f, .34f, .63f), new Color(.77f, .56f, .9f));
        monster.Configure("Хранитель кристалла", RiftTeam.Neutral, RiftUnitKind.Monster, new Vector3(1.8f, 0, -4.5f), 440, 1.65f, 34, 2.2f, 1.35f);
        monster.Died += OnUnitDied;
        SpawnWave();
        Phase = MatchPhase.Playing;
        if (ui != null)
        {
            ui.ShowHud();
            ui.SetRespawn(false, 0);
            ui.SetObjective("ЗАХВАТИ НЕЙТРАЛЬНЫЙ КРИСТАЛЛ И ПРОДВИГАЙСЯ К БАШНЕ");
            ui.RefreshHud();
        }
        SpeakSelected("Матч начинается. Линия за тобой!");
    }

    private UnitActor CreateHero(HeroDefinition definition, RiftTeam team, Vector3 position, bool isPlayer)
    {
        Color main = team == RiftTeam.Blue ? definition.primary : new Color(.54f, .25f, .27f);
        Color accent = team == RiftTeam.Blue ? definition.accent : new Color(.85f, .48f, .37f);
        UnitActor actor = ArenaBuilder.CreateUnit(actorRoot, definition.displayName, team, RiftUnitKind.Hero, position, main, accent, definition, isPlayer ? SelectedSkin : 0);
        actor.Configure(definition.displayName, team, RiftUnitKind.Hero, position, definition.maxHealth,
            definition.moveSpeed, definition.basicDamage, definition.attackRange, definition.attackRate, definition, definition.maxMana);
        actor.Died += OnUnitDied;
        if (isPlayer) Player = actor;
        return actor;
    }

    private void ResetStructure(UnitActor actor, float hp)
    {
        if (actor == null) return;
        actor.gameObject.SetActive(true);
        actor.Configure(actor.displayName, actor.team, actor.kind, actor.transform.position, hp, 0, 0, 7.2f, 1.45f);
    }

    private void ClearActors()
    {
        if (actorRoot != null)
        {
            for (int i = actorRoot.childCount - 1; i >= 0; i--) Destroy(actorRoot.GetChild(i).gameObject);
        }
        Player = null;
        enemyHero = null;
        monster = null;
        minions.Clear();
        attackTimers.Clear();
        respawnTimers.Clear();
    }

    private void UpdatePlayer(float dt)
    {
        if (Player == null || !Player.IsAlive) return;
        Vector2 keyboard = Vector2.zero;
        if (Input.GetKey(KeyCode.LeftArrow)) keyboard.x -= 1;
        if (Input.GetKey(KeyCode.RightArrow)) keyboard.x += 1;
        if (Input.GetKey(KeyCode.DownArrow)) keyboard.y -= 1;
        if (Input.GetKey(KeyCode.UpArrow)) keyboard.y += 1;
        if (keyboard.sqrMagnitude > 1) keyboard.Normalize();
        Vector2 movement = keyboard + touchMove;
        if (movement.sqrMagnitude > 1) movement.Normalize();
        Player.Move(new Vector3(movement.x, 0, movement.y), dt);
        float attackCooldown = GetAttackTimer(Player) - dt;
        SetAttackTimer(Player, attackCooldown);
        if ((Input.GetKey(KeyCode.Space) || touchAttackHeld) && attackCooldown <= 0) BasicAttack(Player);
        if (Input.GetKeyDown(KeyCode.Q)) CastAbility(0);
        if (Input.GetKeyDown(KeyCode.W)) CastAbility(1);
        if (Input.GetKeyDown(KeyCode.E)) CastAbility(2);
        if (Input.GetKeyDown(KeyCode.R)) CastAbility(3);
        if (Input.GetKeyDown(KeyCode.Alpha1)) UsePotion();
        if (Player.Mana < Player.maxMana) Player.mana = Mathf.Min(Player.maxMana, Player.mana + dt * 8f);
    }

    private void UpdateEnemyHero(float dt)
    {
        if (enemyHero == null || !enemyHero.IsAlive) return;
        float attackCooldown = GetAttackTimer(enemyHero) - dt;
        SetAttackTimer(enemyHero, attackCooldown);
        UnitActor target = FindNearest(enemyHero, RiftTeam.Blue, 11.5f, false);
        if (target == null)
        {
            UnitActor structure = EnemyStructureFor(RiftTeam.Red);
            if (structure == null || !structure.IsAlive) return;
            float structureDistance = Vector3.Distance(enemyHero.transform.position, structure.transform.position);
            if (structureDistance > enemyHero.attackRange)
                enemyHero.Move(structure.transform.position - enemyHero.transform.position, dt);
            else if (attackCooldown <= 0)
            {
                enemyHero.Face(structure.transform.position);
                Attack(enemyHero, structure, 48f);
                SetAttackTimer(enemyHero, enemyHero.attackRate);
            }
            return;
        }
        float distance = Vector3.Distance(enemyHero.transform.position, target.transform.position);
        if (distance > enemyHero.attackRange * .86f)
            enemyHero.Move(target.transform.position - enemyHero.transform.position, dt);
        else if (attackCooldown <= 0)
        {
            enemyHero.Face(target.transform.position);
            Attack(enemyHero, target);
            SetAttackTimer(enemyHero, enemyHero.attackRate);
        }
    }

    private void UpdateMinions(float dt)
    {
        for (int i = minions.Count - 1; i >= 0; i--)
        {
            UnitActor minion = minions[i];
            if (minion == null) { minions.RemoveAt(i); continue; }
            if (!minion.IsAlive) continue;
            float attackCooldown = GetAttackTimer(minion) - dt;
            SetAttackTimer(minion, attackCooldown);
            UnitActor target = FindNearest(minion, minion.team == RiftTeam.Blue ? RiftTeam.Red : RiftTeam.Blue, 2.6f, true);
            if (target != null)
            {
                if (Vector3.Distance(minion.transform.position, target.transform.position) > 1.9f)
                    minion.Move(target.transform.position - minion.transform.position, dt);
                else if (attackCooldown <= 0)
                {
                    minion.Face(target.transform.position);
                    Attack(minion, target);
                    SetAttackTimer(minion, 1.3f);
                }
                continue;
            }
            UnitActor structure = EnemyStructureFor(minion.team);
            if (structure == null) continue;
            float structureDistance = Vector3.Distance(minion.transform.position, structure.transform.position);
            if (structureDistance > 2.5f) minion.Move(structure.transform.position - minion.transform.position, dt);
            else if (attackCooldown <= 0)
            {
                Attack(minion, structure, 23f);
                SetAttackTimer(minion, 1.3f);
            }
        }
    }

    private void UpdateNeutralMonster(float dt)
    {
        if (monster == null || !monster.IsAlive || Player == null || !Player.IsAlive) return;
        float distance = Vector3.Distance(monster.transform.position, Player.transform.position);
        if (distance > 7.5f) return;
        float cooldown = GetAttackTimer(monster) - dt;
        SetAttackTimer(monster, cooldown);
        if (distance > 2.2f) monster.Move(Player.transform.position - monster.transform.position, dt);
        else if (cooldown <= 0)
        {
            monster.Face(Player.transform.position);
            Attack(monster, Player);
            SetAttackTimer(monster, 1.35f);
        }
    }

    private void UpdateTowers(float dt)
    {
        foreach (UnitActor tower in structures)
        {
            if (tower == null || !tower.IsAlive || tower.kind != RiftUnitKind.Tower) continue;
            float cooldown = GetAttackTimer(tower) - dt;
            SetAttackTimer(tower, cooldown);
            if (cooldown > 0) continue;
            UnitActor target = FindNearest(tower, tower.team == RiftTeam.Blue ? RiftTeam.Red : RiftTeam.Blue, 7.2f, false);
            if (target == null) continue;
            Attack(tower, target, target.kind == RiftUnitKind.Hero ? 42 : 61);
            SetAttackTimer(tower, 1.45f);
        }
    }

    private void UpdateTimers(float dt)
    {
        for (int i = 0; i < abilityCooldowns.Length; i++) abilityCooldowns[i] = Mathf.Max(0, abilityCooldowns[i] - dt);
        if (jungleBuffUntil > 0 && MatchTime >= jungleBuffUntil && Player != null)
        {
            Player.basicDamage = Mathf.Max(1, Player.basicDamage - jungleBuffBonus);
            jungleBuffBonus = 0;
            jungleBuffUntil = 0;
            if (ui != null) ui.SetBuff(false);
        }
    }

    private void UpdateRespawns()
    {
        if (respawnTimers.Count == 0) return;
        var ready = new List<UnitActor>();
        var snapshot = new List<UnitActor>(respawnTimers.Keys);
        foreach (UnitActor actor in snapshot)
        {
            if (actor == null) { respawnTimers.Remove(actor); continue; }
            float remaining = respawnTimers[actor] - Time.deltaTime;
            if (remaining <= 0) ready.Add(actor);
            else respawnTimers[actor] = remaining;
        }
        foreach (UnitActor actor in ready)
        {
            respawnTimers.Remove(actor);
            if (actor == null) continue;
            actor.gameObject.SetActive(true);
            Vector3 spawn = actor == Player ? new Vector3(-4.1f, 0, -4.1f) : actor == enemyHero ? new Vector3(3.4f, 0, 3.4f) : new Vector3(1.8f, 0, -4.5f);
            actor.Respawn(spawn);
            if (ui != null && actor == Player) ui.SetRespawn(false, 0);
        }
    }

    private void UpdateCamera(float dt)
    {
        if (gameCamera == null) return;
        Vector3 focus = Phase == MatchPhase.Playing && Player != null && Player.IsAlive ? Player.transform.position : new Vector3(-1.2f, 0, -.25f);
        Vector3 targetPosition = focus + new Vector3(0, 20.5f, 18.5f);
        gameCamera.transform.position = Vector3.Lerp(gameCamera.transform.position, targetPosition, Mathf.Clamp01(dt * 3.2f));
        gameCamera.transform.LookAt(focus);
    }

    public void CastAbility(int slot)
    {
        if (Phase != MatchPhase.Playing || Player == null || !Player.IsAlive || slot < 0 || slot >= SelectedHero.abilities.Length) return;
        HeroAbilityData ability = SelectedHero.abilities[slot];
        if (abilityCooldowns[slot] > 0 || Player.mana < ability.manaCost)
        {
            if (Player.mana < ability.manaCost && ui != null) ui.SetObjective("НЕДОСТАТОЧНО МАНЫ");
            return;
        }
        Player.mana -= ability.manaCost;
        abilityCooldowns[slot] = ability.cooldown;
        RiftAudioDirector.PlaySpell(SelectedHero.voicePitch);
        SpeakSelected(ability.displayName + "!");
        Color vfxColor = SelectedHero.glow;
        UnitActor target = FindNearest(Player, RiftTeam.Red, ability.range, true);
        if (slot == 1)
        {
            Vector3 direction = new Vector3(touchMove.x, 0, touchMove.y);
            if (direction.sqrMagnitude < .1f && target != null) direction = target.transform.position - Player.transform.position;
            if (direction.sqrMagnitude < .1f) direction = Player.transform.forward;
            Vector3 origin = Player.transform.position;
            Player.transform.position = Vector3.ClampMagnitude(origin + direction.normalized * 3.6f, 13.5f);
            ArenaBuilder.CreateEffect(worldRoot, origin, .8f, vfxColor, .35f);
            foreach (UnitActor enemy in GetEnemies(RiftTeam.Blue))
                if (enemy != null && enemy.IsAlive && Vector3.Distance(Player.transform.position, enemy.transform.position) <= ability.radius) enemy.ReceiveDamage(ability.damage, Player);
        }
        else if (slot == 2)
        {
            if (target != null)
            {
                ArenaBuilder.CreateEffect(worldRoot, target.transform.position, 1.25f, vfxColor, .42f);
                target.ReceiveDamage(ability.damage, Player);
            }
            else ArenaBuilder.CreateEffect(worldRoot, Player.transform.position + Player.transform.forward * 4, 1.4f, vfxColor, .45f);
        }
        else
        {
            Vector3 center = slot == 3 ? Player.transform.position : target != null ? target.transform.position : Player.transform.position + Player.transform.forward * 2;
            ArenaBuilder.CreateEffect(worldRoot, center, ability.radius, vfxColor, .55f);
            foreach (UnitActor enemy in GetEnemies(RiftTeam.Blue))
                if (enemy != null && enemy.IsAlive && Vector3.Distance(center, enemy.transform.position) <= ability.radius) enemy.ReceiveDamage(ability.damage, Player);
            UnitActor structure = EnemyStructureFor(RiftTeam.Blue);
            if (structure != null && structure.IsAlive && Vector3.Distance(center, structure.transform.position) <= ability.radius) structure.ReceiveDamage(ability.damage * .65f, Player);
        }
        if (ui != null) ui.RefreshHud();
    }

    public void UsePotion()
    {
        if (Phase != MatchPhase.Playing || Player == null || !Player.IsAlive) return;
        Player.Restore(250, 140);
        if (ui != null) ui.RefreshHud();
    }

    public void BasicAttack()
    {
        if (Phase != MatchPhase.Playing || Player == null || !Player.IsAlive || GetAttackTimer(Player) > 0) return;
        UnitActor target = FindNearest(Player, RiftTeam.Red, Player.attackRange + .8f, false);
        if (target == null)
        {
            UnitActor structure = EnemyStructureFor(RiftTeam.Blue);
            if (structure != null && structure.IsAlive && Vector3.Distance(Player.transform.position, structure.transform.position) <= Player.attackRange + .3f) target = structure;
        }
        if (target == null) return;
        Player.Face(target.transform.position);
        Attack(Player, target);
        SetAttackTimer(Player, Player.attackRate);
    }

    private void Attack(UnitActor source, UnitActor target, float overrideDamage = -1)
    {
        if (source == null || target == null || !source.IsAlive || !target.IsAlive) return;
        float damage = overrideDamage >= 0 ? overrideDamage : source.basicDamage;
        if (source == Player) RiftAudioDirector.PlayImpact(SelectedHero.voicePitch);
        target.ReceiveDamage(damage, source);
        ArenaBuilder.CreateEffect(worldRoot, target.transform.position, .5f, source.team == RiftTeam.Blue ? new Color(.38f, .82f, .82f) : new Color(.9f, .4f, .34f), .2f);
    }

    private void OnUnitDied(UnitActor victim, UnitActor source)
    {
        if (victim == null) return;
        if (victim.kind == RiftUnitKind.Core)
        {
            bool victory = victim.team == RiftTeam.Red;
            FinishMatch(victory);
            return;
        }
        if (victim.kind == RiftUnitKind.Tower)
        {
            if (victim.team == RiftTeam.Red && source == Player)
            {
                Gold += 160;
                Player.AddExperience(110);
            }
            if (ui != null) ui.SetObjective(victim.team == RiftTeam.Red ? "ВРАЖЕСКАЯ БАШНЯ РАЗРУШЕНА" : "СОЮЗНАЯ БАШНЯ ПАЛА");
            return;
        }
        if (victim.kind == RiftUnitKind.Monster)
        {
            victim.gameObject.SetActive(false);
            respawnTimers[victim] = 42f;
            if (source == Player)
            {
                Gold += 95;
                Player.AddExperience(125);
                if (jungleBuffBonus > 0) Player.basicDamage -= jungleBuffBonus;
                jungleBuffBonus = 12;
                Player.basicDamage += jungleBuffBonus;
                jungleBuffUntil = MatchTime + 32f;
                if (ui != null) { ui.SetBuff(true); ui.SetObjective("КРИСТАЛЛ ПОВЕРЖЕН · ПОЛУЧЕН БАФФ"); }
            }
            return;
        }
        if (victim.kind == RiftUnitKind.Hero)
        {
            if (victim.team == RiftTeam.Red)
            {
                if (source == Player) { Kills++; Gold += 110; Player.AddExperience(190); }
                respawnTimers[victim] = 7f;
            }
            else
            {
                if (victim == Player)
                {
                    Deaths++;
                    if (ui != null) ui.SetRespawn(true, 6);
                }
                respawnTimers[victim] = victim == Player ? 6f : 7f;
            }
            victim.gameObject.SetActive(false);
            return;
        }
        if (victim.kind == RiftUnitKind.Minion)
        {
            if (source == Player)
            {
                Gold += 28;
                Player.AddExperience(38);
            }
            minions.Remove(victim);
            attackTimers.Remove(victim);
            Destroy(victim.gameObject, 3f);
        }
    }

    private void FinishMatch(bool victory)
    {
        if (Phase != MatchPhase.Playing) return;
        Phase = MatchPhase.Result;
        RiftAudioDirector.PlayVictory();
        SpeakSelected(victory ? "Мы победили!" : "Нужно отступить и попробовать снова.");
        RiftProfileData local = RiftProfileStore.RecordMatch(victory, Kills, Deaths, Assists);
        if (Api != null && Api.IsConnected)
            Api.RecordMatch(victory, Kills, Deaths, Assists, Mathf.FloorToInt(MatchTime), Gold, SelectedHero.heroId,
                (success, message, remote) => { if (success && remote != null) RiftProfileStore.Save(remote); LastStatus = message; });
        if (ui != null) ui.ShowResult(victory, local);
    }

    private void SpawnWave()
    {
        waveNumber++;
        for (int i = 0; i < 3; i++)
        {
            float offset = i * .75f;
            SpawnMinion(RiftTeam.Blue, new Vector3(-9.2f - offset, 0, -9.2f - offset));
            SpawnMinion(RiftTeam.Red, new Vector3(9.2f + offset, 0, 9.2f + offset));
        }
        if (ui != null) ui.SetObjective("ВОЛНА " + waveNumber + " ВЫДВИНУЛАСЬ ПО ЛИНИИ");
    }

    private void SpawnMinion(RiftTeam team, Vector3 position)
    {
        bool blue = team == RiftTeam.Blue;
        UnitActor minion = ArenaBuilder.CreateUnit(actorRoot, (blue ? "Союзный боец " : "Вражеский боец ") + waveNumber,
            team, RiftUnitKind.Minion, position, blue ? new Color(.2f, .47f, .52f) : new Color(.52f, .24f, .29f), new Color(.81f, .64f, .4f));
        minion.Configure(minion.displayName, team, RiftUnitKind.Minion, position, 105, 1.65f, 18, 2.2f, 1.32f);
        minion.Died += OnUnitDied;
        minions.Add(minion);
    }

    private UnitActor FindNearest(UnitActor origin, RiftTeam enemyTeam, float range, bool includeStructures)
    {
        UnitActor best = null;
        float bestDistance = range;
        foreach (UnitActor actor in AllCombatants())
        {
            if (actor == null || !actor.IsAlive || actor == origin) continue;
            bool enemy = actor.team != origin.team && (actor.team == enemyTeam || actor.team == RiftTeam.Neutral);
            if (!enemy || actor.kind == RiftUnitKind.Core || actor.kind == RiftUnitKind.Tower) continue;
            float distance = Vector3.Distance(origin.transform.position, actor.transform.position);
            if (distance < bestDistance) { best = actor; bestDistance = distance; }
        }
        if (includeStructures)
        {
            UnitActor structure = EnemyStructureFor(origin.team);
            if (structure != null && structure.IsAlive && Vector3.Distance(origin.transform.position, structure.transform.position) < bestDistance) best = structure;
        }
        return best;
    }

    private List<UnitActor> AllCombatants()
    {
        var result = new List<UnitActor>(minions.Count + 3);
        result.Add(Player);
        result.Add(enemyHero);
        result.Add(monster);
        result.AddRange(minions);
        return result;
    }

    private List<UnitActor> GetEnemies(RiftTeam ownTeam)
    {
        var result = new List<UnitActor>();
        foreach (UnitActor actor in AllCombatants())
            if (actor != null && actor.team != ownTeam && (actor.team == RiftTeam.Red || actor.team == RiftTeam.Neutral)) result.Add(actor);
        return result;
    }

    private UnitActor EnemyStructureFor(RiftTeam team)
    {
        if (team == RiftTeam.Blue)
        {
            if (redTower != null && redTower.IsAlive) return redTower;
            return redCore;
        }
        if (blueTower != null && blueTower.IsAlive) return blueTower;
        return blueCore;
    }

    private float GetAttackTimer(UnitActor actor)
    {
        if (actor == null || !attackTimers.TryGetValue(actor, out float value)) return 0;
        return value;
    }

    private void SetAttackTimer(UnitActor actor, float value)
    {
        if (actor == null) return;
        attackTimers[actor] = Mathf.Max(0, value);
    }

    public void SetMoveInput(Vector2 input) { touchMove = Vector2.ClampMagnitude(input, 1); }
    public void SetAttackHeld(bool held) { touchAttackHeld = held; if (!held) return; BasicAttack(); }
    public void SetQuality(int value)
    {
        RiftSettingsStore.SetQuality(value);
        if (keyLight != null) keyLight.shadows = value == 0 ? LightShadows.None : LightShadows.Soft;
        if (gameCamera != null) gameCamera.allowMSAA = QualitySettings.antiAliasing > 0;
    }
    public float GetAbilityCooldown(int slot) => slot >= 0 && slot < abilityCooldowns.Length ? abilityCooldowns[slot] : 0;

    public void PauseMatch()
    {
        if (Phase != MatchPhase.Playing) return;
        Phase = MatchPhase.Paused;
        Time.timeScale = 0f;
        RiftAudioDirector.SetPaused(true);
        if (ui != null) ui.ShowPaused();
    }

    public void ResumeMatch()
    {
        if (Phase != MatchPhase.Paused) return;
        Phase = MatchPhase.Playing;
        Time.timeScale = 1f;
        RiftAudioDirector.SetPaused(false);
        if (ui != null) ui.ShowHud();
    }

    public void SetShowcaseVisible(bool visible)
    {
        if (showcaseHero != null) showcaseHero.SetActive(visible);
    }

    public void ReturnToLobby()
    {
        StopAllCoroutines();
        Time.timeScale = 1f;
        RiftAudioDirector.SetPaused(false);
        Phase = MatchPhase.Lobby;
        ClearActors();
        if (showcaseHero != null) showcaseHero.SetActive(true);
        if (ui != null) ui.ShowLobby();
    }

    private void SpeakSelected(string line)
    {
        if (!RiftSettingsStore.Voice || string.IsNullOrEmpty(line)) return;
#if UNITY_ANDROID && !UNITY_EDITOR
        try
        {
            using (AndroidJavaClass unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (AndroidJavaObject activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            using (AndroidJavaClass speech = new AndroidJavaClass("com.modar.riftarena.RiftSpeechPlugin"))
                speech.CallStatic("speak", activity, line, SelectedHero != null ? SelectedHero.voicePitch : 1f, 1f);
        }
        catch (System.Exception exception) { Debug.LogWarning("System speech is not available: " + exception.Message); }
#endif
    }

    private void OnDestroy()
    {
        if (Instance == this)
        {
            Time.timeScale = 1f;
            Instance = null;
        }
    }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void CreateRuntimeGame()
    {
        if (FindObjectOfType<RiftGame>() != null) return;
        var runtime = new GameObject("Rift Arena Runtime");
        runtime.AddComponent<RiftGame>();
    }
}
