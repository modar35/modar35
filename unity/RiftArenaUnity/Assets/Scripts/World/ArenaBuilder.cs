using System.Collections.Generic;
using UnityEngine;

public static class ArenaBuilder
{
    private static readonly Color Grass = new Color(.15f, .28f, .22f);
    private static readonly Color Stone = new Color(.42f, .42f, .36f);
    private static readonly Color Gold = new Color(.82f, .68f, .42f);
    private static readonly Color Water = new Color(.05f, .31f, .35f);
    private static readonly Dictionary<int, Material> MaterialCache = new Dictionary<int, Material>();

    public static void BuildWorld(Transform root)
    {
        CreatePrimitive(root, PrimitiveType.Cube, "Arena Foundation", new Vector3(0, -.42f, 0), new Vector3(36, .65f, 36), new Color(.08f, .13f, .12f));
        CreatePrimitive(root, PrimitiveType.Plane, "Custom Meadow", new Vector3(0, -.08f, 0), new Vector3(3.5f, 1, 3.5f), Grass);

        // The authored layout is symmetric around the central diagonal lane.
        GameObject lane = new GameObject("Dusk Ridge · Mid Lane");
        lane.transform.SetParent(root, false);
        lane.transform.position = Vector3.zero;
        lane.transform.rotation = Quaternion.Euler(0, 45, 0);
        CreatePrimitive(lane.transform, PrimitiveType.Cube, "Paved Lane", new Vector3(0, .015f, 0), new Vector3(5, .08f, 36), new Color(.48f, .46f, .38f));
        CreatePrimitive(lane.transform, PrimitiveType.Cube, "Lane Edge West", new Vector3(-2.6f, .07f, 0), new Vector3(.12f, .12f, 36), Gold);
        CreatePrimitive(lane.transform, PrimitiveType.Cube, "Lane Edge East", new Vector3(2.6f, .07f, 0), new Vector3(.12f, .12f, 36), Gold);

        GameObject river = new GameObject("Quiet River");
        river.transform.SetParent(root, false);
        river.transform.rotation = Quaternion.Euler(0, -45, 0);
        CreatePrimitive(river.transform, PrimitiveType.Cube, "River", new Vector3(0, -.005f, 0), new Vector3(2.2f, .035f, 26), Water);
        GameObject bridge = new GameObject("Runed Crossing");
        bridge.transform.SetParent(root, false);
        bridge.transform.rotation = Quaternion.Euler(0, 45, 0);
        CreatePrimitive(bridge.transform, PrimitiveType.Cube, "Bridge Deck", new Vector3(0, .16f, 0), new Vector3(5.5f, .28f, 2.8f), new Color(.72f, .67f, .53f));
        for (int i = -2; i <= 2; i++)
            CreatePrimitive(bridge.transform, PrimitiveType.Cube, "Bridge Rune", new Vector3(i * 1.05f, .31f, 0), new Vector3(.055f, .025f, 2.3f), new Color(.37f, .86f, .79f));

        // Original jungle shrine and two asymmetric tree belts.
        CreateShrine(root, new Vector3(1.8f, 0, -4.5f));
        CreateTreeBelt(root, -1);
        CreateTreeBelt(root, 1);
        for (int i = 0; i < 12; i++)
        {
            float t = i * Mathf.PI * 2 / 12;
            Vector3 p = new Vector3(Mathf.Cos(t) * 4.1f, .14f, Mathf.Sin(t) * 4.1f);
            CreatePrimitive(root, PrimitiveType.Cylinder, "Broken Rune Pillar", p, new Vector3(.26f, .34f + (i % 3) * .09f, .26f), i % 2 == 0 ? Stone : new Color(.27f, .37f, .34f));
        }

        GameObject dais = new GameObject("Champion Showcase Dais");
        dais.transform.SetParent(root, false);
        dais.transform.position = new Vector3(2.45f, 0, -.2f);
        CreatePrimitive(dais.transform, PrimitiveType.Cylinder, "Dais Stone", new Vector3(0, .1f, 0), new Vector3(3.3f, .25f, 3.3f), new Color(.26f, .33f, .3f));
        CreatePrimitive(dais.transform, PrimitiveType.Cylinder, "Dais Rune", new Vector3(0, .25f, 0), new Vector3(2.8f, .045f, 2.8f), Gold);
    }

    public static UnitActor CreateUnit(Transform parent, string name, RiftTeam team, RiftUnitKind kind,
        Vector3 position, Color mainColor, Color accentColor, HeroDefinition hero = null, int skinIndex = 0)
    {
        GameObject unitRoot = new GameObject(name);
        unitRoot.transform.SetParent(parent, false);
        unitRoot.transform.position = position;
        UnitActor actor = unitRoot.AddComponent<UnitActor>();
        actor.team = team;
        actor.kind = kind;
        if (kind == RiftUnitKind.Monster)
            CreateMonsterShape(unitRoot.transform);
        else if (kind == RiftUnitKind.Hero && hero != null)
            CreateHeroShape(unitRoot.transform, hero, mainColor, accentColor, skinIndex);
        else if (kind == RiftUnitKind.Tower || kind == RiftUnitKind.Core)
            CreateStructureShape(unitRoot.transform, kind, mainColor, accentColor);
        else
            CreateMinionShape(unitRoot.transform, mainColor, accentColor);
        CreateHealthBar(unitRoot.transform, kind == RiftUnitKind.Hero ? 2.8f : kind == RiftUnitKind.Core ? 3.1f : 2.4f, team);
        return actor;
    }

    private static void CreateStructureShape(Transform root, RiftUnitKind kind, Color primary, Color accent)
    {
        bool core = kind == RiftUnitKind.Core;
        CreatePrimitive(root, PrimitiveType.Cylinder, "Structure Foot", new Vector3(0, .18f, 0), core ? new Vector3(3.2f, .35f, 3.2f) : new Vector3(2.2f, .35f, 2.2f), new Color(.32f, .36f, .32f));
        if (core)
        {
            CreatePrimitive(root, PrimitiveType.Sphere, "Base Crystal", new Vector3(0, 1.45f, 0), new Vector3(1.35f, 2f, 1.35f), primary);
            CreatePrimitive(root, PrimitiveType.Cylinder, "Crystal Ring", new Vector3(0, 1.4f, 0), new Vector3(2.5f, .08f, 2.5f), accent);
        }
        else
        {
            CreatePrimitive(root, PrimitiveType.Cylinder, "Tower Shaft", new Vector3(0, .95f, 0), new Vector3(1.15f, 1.1f, 1.15f), accent);
            CreatePrimitive(root, PrimitiveType.Sphere, "Tower Crystal", new Vector3(0, 1.95f, 0), new Vector3(.62f, .9f, .62f), primary);
        }
    }

    public static GameObject CreateEffect(Transform parent, Vector3 position, float radius, Color color, float duration)
    {
        GameObject effect = GameObject.CreatePrimitive(PrimitiveType.Sphere);
        effect.name = "Rift VFX";
        effect.transform.SetParent(parent, false);
        effect.transform.position = position + Vector3.up * .18f;
        effect.transform.localScale = Vector3.one * Mathf.Max(.25f, radius * .35f);
        Collider collider = effect.GetComponent<Collider>();
        if (collider != null) Object.Destroy(collider);
        Renderer renderer = effect.GetComponent<Renderer>();
        renderer.sharedMaterial = MakeMaterial(color, true);
        effect.AddComponent<SimpleVfx>().Initialize(radius, duration);
        return effect;
    }

    private static void CreateHeroShape(Transform root, HeroDefinition hero, Color primary, Color accent, int skinIndex)
    {
        if (skinIndex % 2 == 1)
        {
            Color.RGBToHSV(primary, out float hue, out float saturation, out float value);
            primary = Color.HSVToRGB(Mathf.Repeat(hue + .19f, 1f), Mathf.Clamp01(saturation + .05f), Mathf.Clamp01(value + .04f));
            accent = Color.HSVToRGB(Mathf.Repeat(hue + .31f, 1f), .42f, .96f);
        }
        CreatePrimitive(root, PrimitiveType.Capsule, "Armored Body", new Vector3(0, 1.05f, 0), new Vector3(.72f, .88f, .62f), primary);
        CreatePrimitive(root, PrimitiveType.Sphere, "Head", new Vector3(0, 2.03f, .02f), new Vector3(.47f, .5f, .45f), new Color(.76f, .63f, .52f));
        CreatePrimitive(root, PrimitiveType.Capsule, "Cape", new Vector3(0, .94f, -.39f), new Vector3(.78f, .85f, .22f), accent);
        CreatePrimitive(root, PrimitiveType.Cube, "Weapon", new Vector3(.59f, 1.42f, .2f), new Vector3(.14f, 1.05f, .12f), hero.glow);
        CreatePrimitive(root, PrimitiveType.Sphere, "Chest Crystal", new Vector3(0, 1.34f, .36f), new Vector3(.21f, .21f, .12f), hero.glow);
        if (hero.heroId == "elaris")
        {
            GameObject halo = CreatePrimitive(root, PrimitiveType.Cylinder, "Dawn Halo", new Vector3(0, 2.42f, 0), new Vector3(.83f, .035f, .83f), hero.glow);
            halo.transform.localScale = new Vector3(.8f, .04f, .8f);
            CreatePrimitive(root, PrimitiveType.Sphere, "Floating Shard", new Vector3(.66f, 1.52f, -.18f), Vector3.one * .22f, accent);
        }
        else if (hero.heroId == "raen")
        {
            CreatePrimitive(root, PrimitiveType.Cube, "Twin Dagger L", new Vector3(-.58f, 1.37f, .16f), new Vector3(.1f, .65f, .08f), hero.glow);
            CreatePrimitive(root, PrimitiveType.Cube, "Twin Dagger R", new Vector3(.57f, 1.38f, .17f), new Vector3(.1f, .65f, .08f), hero.glow);
            CreatePrimitive(root, PrimitiveType.Cylinder, "Storm Hood", new Vector3(0, 2.38f, -.12f), new Vector3(.48f, .55f, .48f), accent);
        }
        else
        {
            CreatePrimitive(root, PrimitiveType.Cube, "Stone Shield", new Vector3(-.65f, 1.15f, .22f), new Vector3(.52f, .72f, .22f), accent);
            CreatePrimitive(root, PrimitiveType.Cube, "War Hammer Head", new Vector3(.72f, 1.94f, .19f), new Vector3(.55f, .28f, .35f), hero.glow);
            CreatePrimitive(root, PrimitiveType.Sphere, "Heavy Pauldron L", new Vector3(-.48f, 1.48f, 0), Vector3.one * .48f, accent);
            CreatePrimitive(root, PrimitiveType.Sphere, "Heavy Pauldron R", new Vector3(.48f, 1.48f, 0), Vector3.one * .48f, accent);
        }
    }

    private static void CreateMinionShape(Transform root, Color primary, Color accent)
    {
        CreatePrimitive(root, PrimitiveType.Capsule, "Minion Body", new Vector3(0, .58f, 0), new Vector3(.48f, .5f, .42f), primary);
        CreatePrimitive(root, PrimitiveType.Sphere, "Minion Helmet", new Vector3(0, 1.18f, .02f), new Vector3(.38f, .36f, .34f), accent);
        CreatePrimitive(root, PrimitiveType.Cube, "Minion Blade", new Vector3(.37f, .9f, .08f), new Vector3(.08f, .58f, .08f), Gold);
    }

    private static void CreateMonsterShape(Transform root)
    {
        CreatePrimitive(root, PrimitiveType.Sphere, "Crystal Guardian", new Vector3(0, .96f, 0), new Vector3(1.35f, 1.35f, 1.1f), new Color(.31f, .25f, .42f));
        CreatePrimitive(root, PrimitiveType.Sphere, "Guardian Core", new Vector3(0, 1.1f, .48f), new Vector3(.58f, .62f, .34f), new Color(.71f, .48f, .89f));
        CreatePrimitive(root, PrimitiveType.Cylinder, "Horn L", new Vector3(-.45f, 1.87f, -.02f), new Vector3(.3f, .82f, .3f), new Color(.65f, .48f, .8f));
        CreatePrimitive(root, PrimitiveType.Cylinder, "Horn R", new Vector3(.45f, 1.87f, -.02f), new Vector3(.3f, .82f, .3f), new Color(.65f, .48f, .8f));
        CreatePrimitive(root, PrimitiveType.Capsule, "Arm L", new Vector3(-.78f, .75f, .03f), new Vector3(.36f, .56f, .35f), new Color(.38f, .3f, .48f));
        CreatePrimitive(root, PrimitiveType.Capsule, "Arm R", new Vector3(.78f, .75f, .03f), new Vector3(.36f, .56f, .35f), new Color(.38f, .3f, .48f));
    }

    private static void CreateHealthBar(Transform parent, float height, RiftTeam team)
    {
        GameObject bar = new GameObject("Health Bar");
        bar.transform.SetParent(parent, false);
        bar.transform.localPosition = Vector3.up * height;
        bar.transform.localScale = new Vector3(1.1f, .09f, .02f);
        bar.AddComponent<HealthBarBillboard>().Initialize(team);
    }

    private static void CreateShrine(Transform root, Vector3 position)
    {
        GameObject shrine = new GameObject("Neutral Crystal Shrine");
        shrine.transform.SetParent(root, false);
        shrine.transform.position = position;
        CreatePrimitive(shrine.transform, PrimitiveType.Cylinder, "Rune Dais", new Vector3(0, .03f, 0), new Vector3(3.1f, .24f, 3.1f), new Color(.24f, .22f, .29f));
        CreatePrimitive(shrine.transform, PrimitiveType.Cylinder, "Dais Inlay", new Vector3(0, .19f, 0), new Vector3(2.45f, .12f, 2.45f), new Color(.54f, .43f, .58f));
        for (int i = 0; i < 8; i++)
        {
            float angle = i * Mathf.PI / 4;
            Vector3 p = new Vector3(Mathf.Cos(angle) * 1.4f, .47f, Mathf.Sin(angle) * 1.4f);
            CreatePrimitive(shrine.transform, PrimitiveType.Sphere, "Shrine Shard", p, new Vector3(.23f, .62f, .23f), new Color(.68f, .49f, .85f));
        }
    }

    private static void CreateTreeBelt(Transform root, int side)
    {
        for (int i = 0; i < 14; i++)
        {
            float t = -11 + i * 1.7f;
            Vector3 position = new Vector3(t - side * 6f * .707f, 0, t + side * 6f * .707f);
            if (Mathf.Abs(position.x) > 14.5f || Mathf.Abs(position.z) > 14.5f) continue;
            GameObject tree = new GameObject("Duskwood Tree");
            tree.transform.SetParent(root, false);
            tree.transform.position = position;
            float scale = .8f + (i % 4) * .15f;
            CreatePrimitive(tree.transform, PrimitiveType.Cylinder, "Trunk", new Vector3(0, .56f * scale, 0), new Vector3(.22f * scale, .65f * scale, .22f * scale), new Color(.27f, .2f, .14f));
            CreatePrimitive(tree.transform, PrimitiveType.Cylinder, "Canopy Lower", new Vector3(0, 1.08f * scale, 0), new Vector3(1.15f * scale, .9f * scale, 1.15f * scale), new Color(.14f, .33f, .24f));
            CreatePrimitive(tree.transform, PrimitiveType.Cylinder, "Canopy Upper", new Vector3(0, 1.62f * scale, 0), new Vector3(.8f * scale, .82f * scale, .8f * scale), new Color(.19f, .4f, .28f));
        }
    }

    private static void CreateBase(Transform root, string name, Vector3 position, Color crystalColor)
    {
        GameObject structure = new GameObject(name);
        structure.transform.SetParent(root, false);
        structure.transform.position = position;
        CreatePrimitive(structure.transform, PrimitiveType.Cylinder, "Core Pedestal", new Vector3(0, .22f, 0), new Vector3(3.5f, .34f, 3.5f), new Color(.28f, .34f, .31f));
        CreatePrimitive(structure.transform, PrimitiveType.Sphere, "Core Crystal", new Vector3(0, 1.4f, 0), new Vector3(1.35f, 2.15f, 1.35f), crystalColor);
        CreatePrimitive(structure.transform, PrimitiveType.Cylinder, "Core Ring", new Vector3(0, 1.5f, 0), new Vector3(2.25f, .08f, 2.25f), Gold);
    }

    private static void CreateTower(Transform root, string name, Vector3 position, Color crystalColor)
    {
        GameObject structure = new GameObject(name);
        structure.transform.SetParent(root, false);
        structure.transform.position = position;
        CreatePrimitive(structure.transform, PrimitiveType.Cylinder, "Tower Foot", new Vector3(0, .2f, 0), new Vector3(2.2f, .35f, 2.2f), new Color(.3f, .35f, .31f));
        CreatePrimitive(structure.transform, PrimitiveType.Cylinder, "Tower Shaft", new Vector3(0, 1.0f, 0), new Vector3(1.15f, 1.2f, 1.15f), new Color(.42f, .4f, .33f));
        CreatePrimitive(structure.transform, PrimitiveType.Sphere, "Tower Crystal", new Vector3(0, 2.05f, 0), new Vector3(.75f, 1.3f, .75f), crystalColor);
    }

    private static GameObject CreatePrimitive(Transform parent, PrimitiveType type, string name, Vector3 localPosition, Vector3 localScale, Color color)
    {
        GameObject obj = GameObject.CreatePrimitive(type);
        obj.name = name;
        obj.transform.SetParent(parent, false);
        obj.transform.localPosition = localPosition;
        obj.transform.localScale = localScale;
        Renderer renderer = obj.GetComponent<Renderer>();
        if (renderer != null) renderer.sharedMaterial = MakeMaterial(color, color.maxColorComponent > .65f);
        Collider collider = obj.GetComponent<Collider>();
        if (collider != null) Object.Destroy(collider);
        return obj;
    }

    private static Material MakeMaterial(Color color, bool emissive)
    {
        int key = color.GetHashCode() * 397 ^ emissive.GetHashCode();
        if (MaterialCache.TryGetValue(key, out Material cached)) return cached;
        Shader shader = Shader.Find("Standard");
        if (shader == null) shader = Shader.Find("Sprites/Default");
        if (shader == null) return null;
        Material material = new Material(shader);
        material.color = color;
        if (shader.name == "Standard")
        {
            material.SetFloat("_Metallic", emissive ? .18f : .04f);
            material.SetFloat("_Glossiness", emissive ? .5f : .22f);
            if (emissive)
            {
                material.EnableKeyword("_EMISSION");
                material.SetColor("_EmissionColor", color * .24f);
            }
        }
        MaterialCache[key] = material;
        return material;
    }
}
