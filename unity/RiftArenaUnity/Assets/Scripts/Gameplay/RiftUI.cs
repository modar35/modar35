using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

public sealed class RiftUI : MonoBehaviour
{
    private static readonly Color Ink = new Color(.035f, .065f, .063f, .97f);
    private static readonly Color Panel = new Color(.075f, .12f, .115f, .96f);
    private static readonly Color PanelSoft = new Color(.09f, .15f, .14f, .86f);
    private static readonly Color Gold = new Color(.84f, .69f, .42f);
    private static readonly Color Cyan = new Color(.4f, .84f, .84f);
    private static readonly Color TextPrimary = new Color(.94f, .92f, .83f);
    private static readonly Color TextMuted = new Color(.66f, .73f, .69f);

    private static Sprite circleSprite;

    private RiftGame game;
    private Canvas canvas;
    private Font font;
    private GameObject lobbyPanel;
    private GameObject loadingPanel;
    private GameObject hudPanel;
    private GameObject pausePanel;
    private GameObject resultPanel;
    private GameObject modalOverlay;
    private RectTransform modalContent;
    private Text heroNameText;
    private Text heroMetaText;
    private Text heroIntroText;
    private Text skinText;
    private Text loadingStageText;
    private Image loadingProgress;
    private Text objectiveText;
    private Text scoreText;
    private Text timeText;
    private Text levelText;
    private Text healthText;
    private Text manaText;
    private Text goldText;
    private Text buffText;
    private Text respawnText;
    private GameObject respawnOverlay;
    private Image healthFill;
    private Image manaFill;
    private Text[] abilityCooldownTexts = new Text[4];
    private Text[] abilityKeyTexts = new Text[4];
    private Button[] abilityButtons = new Button[4];
    private RectTransform minimapRoot;
    private RectTransform playerMarker;
    private RectTransform enemyMarker;
    private string objectiveValue = "ЗАХВАТИ НЕЙТРАЛЬНЫЙ КРИСТАЛЛ И ПРОДВИГАЙСЯ К БАШНЕ";
    private float respawnCountdown;
    private string authStatus = string.Empty;

    public void Initialize(RiftGame owner)
    {
        game = owner;
        font = Resources.GetBuiltinResource<Font>("Arial.ttf");
        if (font == null) font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
        BuildCanvas();
        BuildLobby();
        BuildLoading();
        BuildHud();
        BuildPause();
        BuildResult();
        BuildModal();
        ShowLobby();
        RefreshHeroSelection();
        RefreshHud();
    }

    private void BuildCanvas()
    {
        GameObject canvasObject = new GameObject("Rift Arena UI", typeof(RectTransform), typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
        canvasObject.transform.SetParent(transform, false);
        canvas = canvasObject.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = 100;
        CanvasScaler scaler = canvasObject.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1600, 900);
        scaler.screenMatchMode = CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
        scaler.matchWidthOrHeight = .5f;
        if (FindObjectOfType<EventSystem>() == null)
        {
            GameObject eventObject = new GameObject("Rift Event System", typeof(EventSystem), typeof(StandaloneInputModule));
            eventObject.transform.SetParent(transform, false);
        }
    }

    private void BuildLobby()
    {
        lobbyPanel = MakePanel(canvas.transform, "Champion Select", new Color(.025f, .052f, .05f, .16f), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform root = lobbyPanel.GetComponent<RectTransform>();
        MakeImage(root, "Left Scrim", new Color(.026f, .048f, .045f, .92f), new Vector2(0, 0), new Vector2(.47f, 1), Vector2.zero, Vector2.zero);
        MakeImage(root, "Header Band", new Color(.026f, .045f, .043f, .87f), new Vector2(0, .86f), new Vector2(1, 1), Vector2.zero, Vector2.zero);
        MakeText(root, "Game Title", "СУМЕРЕЧНЫЙ РАЗЛОМ", 28, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.045f, .875f), new Vector2(.47f, .965f), Vector2.zero, Vector2.zero);
        MakeText(root, "Game Subtitle", "ОДИНОЧНАЯ ТРЕНИРОВКА  ·  ЛИНИЯ СУМЕРЕК", 12, FontStyle.Normal, TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.047f, .84f), new Vector2(.47f, .885f), Vector2.zero, Vector2.zero);
        CreateButton(root, "Profile Shortcut", "ПРОФИЛЬ", new Vector2(.73f, .885f), new Vector2(.835f, .965f), ShowProfile, PanelSoft, 15);
        CreateButton(root, "Settings Shortcut", "НАСТРОЙКИ", new Vector2(.845f, .885f), new Vector2(.97f, .965f), ShowSettings, PanelSoft, 15);

        MakeImage(root, "Champion Card", Panel, new Vector2(.045f, .19f), new Vector2(.435f, .81f), Vector2.zero, Vector2.zero);
        MakeImage(root, "Champion Card Accent", Gold, new Vector2(.045f, .19f), new Vector2(.052f, .81f), Vector2.zero, Vector2.zero);
        MakeText(root, "Pick Label", "ИЗБРАННЫЙ ЧЕМПИОН", 12, FontStyle.Bold, Cyan, TextAnchor.MiddleLeft,
            new Vector2(.075f, .735f), new Vector2(.4f, .79f), Vector2.zero, Vector2.zero);
        heroNameText = MakeText(root, "Hero Name", "ЭЛАРИС", 32, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.075f, .655f), new Vector2(.4f, .75f), Vector2.zero, Vector2.zero);
        heroMetaText = MakeText(root, "Hero Role", "МАГ  ·  СРЕДНЯЯ СЛОЖНОСТЬ", 14, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.075f, .59f), new Vector2(.4f, .66f), Vector2.zero, Vector2.zero);
        heroIntroText = MakeText(root, "Hero Story", "Хранительница рассвета", 15, FontStyle.Normal, TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.075f, .455f), new Vector2(.4f, .585f), Vector2.zero, Vector2.zero);
        MakeText(root, "Champion Stats", "ЗДОРОВЬЕ     МАНА       УРОН       СКОРОСТЬ", 10, FontStyle.Bold, TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.075f, .405f), new Vector2(.4f, .46f), Vector2.zero, Vector2.zero);
        MakeText(root, "Champion Stats Values", "780             500          39             3.7", 15, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.075f, .365f), new Vector2(.4f, .415f), Vector2.zero, Vector2.zero);

        CreateButton(root, "Previous Champion", "‹", new Vector2(.075f, .28f), new Vector2(.125f, .34f), () => game.SelectHero(-1), PanelSoft, 25);
        skinText = MakeText(root, "Skin Name", "ОБЛИК · РАССВЕТНАЯ СТРАЖА", 12, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.13f, .285f), new Vector2(.335f, .335f), Vector2.zero, Vector2.zero);
        CreateButton(root, "Next Champion", "›", new Vector2(.34f, .28f), new Vector2(.39f, .34f), () => game.SelectHero(1), PanelSoft, 25);
        CreateButton(root, "Previous Skin", "−", new Vector2(.105f, .22f), new Vector2(.15f, .27f), () => game.SelectSkin(-1), PanelSoft, 17);
        CreateButton(root, "Next Skin", "+", new Vector2(.315f, .22f), new Vector2(.36f, .27f), () => game.SelectSkin(1), PanelSoft, 17);
        MakeText(root, "Skin Hint", "У КАЖДОГО ГЕРОЯ ДВА ЦВЕТОВЫХ ОБЛИКА", 10, FontStyle.Normal, TextMuted, TextAnchor.MiddleCenter,
            new Vector2(.15f, .22f), new Vector2(.315f, .27f), Vector2.zero, Vector2.zero);

        CreateButton(root, "Start Training", "НАЧАТЬ ТРЕНИРОВКУ", new Vector2(.055f, .075f), new Vector2(.275f, .17f), () => game.StartMatch(), new Color(.69f, .47f, .24f), 17);
        CreateButton(root, "Roster Button", "ГЕРОИ", new Vector2(.29f, .075f), new Vector2(.385f, .17f), ShowRoster, PanelSoft, 14);
        MakeText(root, "Offline Label", "3D-ПОЛИГОН  ·  ИИ-СОПЕРНИК  ·  БЕЗ СЕТЕВОГО ПОДБОРА", 10, FontStyle.Normal, TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.055f, .025f), new Vector2(.44f, .07f), Vector2.zero, Vector2.zero);
        MakeText(root, "World Notice", "ОДНА ЛИНИЯ  /  ДВА ЯДРА  /  ОДИН РАЗЛОМ", 11, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.52f, .055f), new Vector2(.96f, .12f), Vector2.zero, Vector2.zero);
    }

    private void BuildLoading()
    {
        loadingPanel = MakePanel(canvas.transform, "Loading Screen", new Color(.025f, .052f, .05f, .96f), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform root = loadingPanel.GetComponent<RectTransform>();
        MakeText(root, "Loading Title", "ПОДГОТОВКА АРЕНЫ", 30, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.2f, .54f), new Vector2(.8f, .66f), Vector2.zero, Vector2.zero);
        loadingStageText = MakeText(root, "Loading Stage", "Загружаем модели героя…", 17, FontStyle.Normal, TextPrimary, TextAnchor.MiddleCenter,
            new Vector2(.2f, .43f), new Vector2(.8f, .52f), Vector2.zero, Vector2.zero);
        MakeImage(root, "Loading Track", Panel, new Vector2(.31f, .365f), new Vector2(.69f, .395f), Vector2.zero, Vector2.zero);
        loadingProgress = MakeImage(root, "Loading Progress", Gold, new Vector2(.31f, .365f), new Vector2(.31f, .395f), Vector2.zero, Vector2.zero);
        MakeText(root, "Loading Tip", "Каждая тренировка — шаг к сердцу вражеской базы.", 12, FontStyle.Normal, TextMuted, TextAnchor.MiddleCenter,
            new Vector2(.2f, .28f), new Vector2(.8f, .35f), Vector2.zero, Vector2.zero);
    }

    private void BuildHud()
    {
        hudPanel = MakePanel(canvas.transform, "Match HUD", new Color(0, 0, 0, 0), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform root = hudPanel.GetComponent<RectTransform>();
        MakeImage(root, "HUD Top Bar", new Color(.025f, .052f, .05f, .91f), new Vector2(0, .88f), new Vector2(1, 1), Vector2.zero, Vector2.zero);
        CreateButton(root, "Pause", "Ⅱ", new Vector2(.012f, .9f), new Vector2(.065f, .98f), () => game.PauseMatch(), PanelSoft, 19);
        objectiveText = MakeText(root, "Objective", objectiveValue, 13, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.078f, .91f), new Vector2(.48f, .975f), Vector2.zero, Vector2.zero);
        scoreText = MakeText(root, "Score", "0 / 0 / 0", 17, FontStyle.Bold, TextPrimary, TextAnchor.MiddleCenter,
            new Vector2(.48f, .915f), new Vector2(.62f, .975f), Vector2.zero, Vector2.zero);
        timeText = MakeText(root, "Match Time", "00:00", 16, FontStyle.Bold, Cyan, TextAnchor.MiddleCenter,
            new Vector2(.62f, .915f), new Vector2(.7f, .975f), Vector2.zero, Vector2.zero);
        CreateMinimap(root);

        MakeImage(root, "Vitals Card", new Color(.025f, .052f, .05f, .88f), new Vector2(.012f, .035f), new Vector2(.275f, .205f), Vector2.zero, Vector2.zero);
        levelText = MakeText(root, "Level", "УРОВЕНЬ 1", 14, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.027f, .155f), new Vector2(.135f, .193f), Vector2.zero, Vector2.zero);
        goldText = MakeText(root, "Gold", "◈ 245", 14, FontStyle.Bold, Gold, TextAnchor.MiddleRight,
            new Vector2(.15f, .155f), new Vector2(.26f, .193f), Vector2.zero, Vector2.zero);
        MakeImage(root, "Health Track", new Color(.16f, .19f, .17f, 1), new Vector2(.027f, .112f), new Vector2(.25f, .142f), Vector2.zero, Vector2.zero);
        healthFill = MakeImage(root, "Health Fill", new Color(.42f, .78f, .56f, 1), new Vector2(.027f, .112f), new Vector2(.25f, .142f), Vector2.zero, Vector2.zero);
        healthFill.type = Image.Type.Filled;
        healthFill.fillMethod = Image.FillMethod.Horizontal;
        healthFill.fillOrigin = (int)Image.OriginHorizontal.Left;
        healthText = MakeText(root, "Health Label", "ЗДОРОВЬЕ  0 / 0", 10, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.027f, .083f), new Vector2(.25f, .112f), Vector2.zero, Vector2.zero);
        MakeImage(root, "Mana Track", new Color(.16f, .19f, .17f, 1), new Vector2(.027f, .049f), new Vector2(.25f, .072f), Vector2.zero, Vector2.zero);
        manaFill = MakeImage(root, "Mana Fill", new Color(.37f, .63f, .87f, 1), new Vector2(.027f, .049f), new Vector2(.25f, .072f), Vector2.zero, Vector2.zero);
        manaFill.type = Image.Type.Filled;
        manaFill.fillMethod = Image.FillMethod.Horizontal;
        manaFill.fillOrigin = (int)Image.OriginHorizontal.Left;
        manaText = MakeText(root, "Mana Label", "МАНА  0 / 0", 10, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.027f, .02f), new Vector2(.25f, .049f), Vector2.zero, Vector2.zero);
        buffText = MakeText(root, "Jungle Buff", "✦ БАФФ КРИСТАЛЛА", 11, FontStyle.Bold, Cyan, TextAnchor.MiddleLeft,
            new Vector2(.29f, .035f), new Vector2(.48f, .085f), Vector2.zero, Vector2.zero);
        buffText.gameObject.SetActive(false);

        GameObject joystick = MakePanel(root, "Virtual Joystick", new Color(.08f, .13f, .12f, .58f),
            new Vector2(.027f, .255f), new Vector2(.195f, .465f), Vector2.zero, Vector2.zero);
        joystick.GetComponent<Image>().sprite = CreateCircleSprite();
        joystick.GetComponent<Image>().type = Image.Type.Simple;
        joystick.GetComponent<Image>().preserveAspect = true;
        RectTransform knob = MakeImage(joystick.transform, "Joystick Knob", new Color(.64f, .82f, .74f, .6f), new Vector2(.32f, .32f), new Vector2(.68f, .68f), Vector2.zero, Vector2.zero).rectTransform;
        knob.GetComponent<Image>().sprite = CreateCircleSprite();
        knob.GetComponent<Image>().preserveAspect = true;
        RiftJoystick joystickHandler = joystick.AddComponent<RiftJoystick>();
        joystickHandler.Initialize(game, joystick.GetComponent<RectTransform>(), knob);
        MakeText(joystick.transform, "Joystick Hint", "MOVE", 10, FontStyle.Bold, TextMuted, TextAnchor.MiddleCenter,
            new Vector2(.25f, .42f), new Vector2(.75f, .58f), Vector2.zero, Vector2.zero);

        CreateActionButton(root, "Attack Button", "УДАР", new Vector2(.89f, .055f), new Vector2(.99f, .245f), new Color(.65f, .3f, .25f), -1);
        Vector2[] actionMins = { new Vector2(.71f, .055f), new Vector2(.78f, .16f), new Vector2(.78f, .055f), new Vector2(.85f, .26f) };
        Vector2[] actionMaxs = { new Vector2(.775f, .17f), new Vector2(.845f, .275f), new Vector2(.845f, .17f), new Vector2(.93f, .38f) };
        for (int i = 0; i < 4; i++) CreateActionButton(root, "Ability " + i, "Q", actionMins[i], actionMaxs[i], new Color(.16f, .35f, .37f), i);
        CreateActionButton(root, "Potion", "ЗЕЛЬЕ", new Vector2(.67f, .055f), new Vector2(.705f, .115f), new Color(.36f, .3f, .48f), 4);

        respawnOverlay = MakePanel(root, "Respawn Overlay", new Color(.02f, .035f, .04f, .82f), new Vector2(.36f, .38f), new Vector2(.64f, .62f), Vector2.zero, Vector2.zero);
        respawnText = MakeText(respawnOverlay.transform, "Respawn Text", "ВОЗВРАЩЕНИЕ ЧЕРЕЗ 6", 22, FontStyle.Bold, TextPrimary, TextAnchor.MiddleCenter,
            Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        respawnOverlay.SetActive(false);
    }

    private void CreateActionButton(RectTransform parent, string name, string label, Vector2 min, Vector2 max, Color color, int slot)
    {
        GameObject buttonObject = MakePanel(parent, name, color, min, max, Vector2.zero, Vector2.zero);
        Image image = buttonObject.GetComponent<Image>();
        image.sprite = CreateCircleSprite();
        image.preserveAspect = true;
        Button button = buttonObject.AddComponent<Button>();
        button.targetGraphic = image;
        ColorBlock colors = button.colors;
        colors.normalColor = color;
        colors.highlightedColor = Color.Lerp(color, Color.white, .18f);
        colors.pressedColor = Color.Lerp(color, Color.black, .14f);
        colors.selectedColor = Color.Lerp(color, Color.white, .1f);
        button.colors = colors;
        Text key = MakeText(buttonObject.transform, name + " Key", label, slot == -1 ? 14 : 17, FontStyle.Bold, TextPrimary, TextAnchor.MiddleCenter,
            new Vector2(.06f, .35f), new Vector2(.94f, .95f), Vector2.zero, Vector2.zero);
        Text sub = MakeText(buttonObject.transform, name + " Sub", slot == -1 ? "АТАКА" : slot == 4 ? "1" : "", 9, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.05f, .12f), new Vector2(.95f, .38f), Vector2.zero, Vector2.zero);
        if (slot == -1)
        {
            buttonObject.AddComponent<RiftHoldButton>().Initialize(game);
            return;
        }
        if (slot == 4)
        {
            button.onClick.AddListener(game.UsePotion);
            return;
        }
        int ability = slot;
        abilityButtons[ability] = button;
        abilityCooldownTexts[ability] = sub;
        abilityKeyTexts[ability] = key;
        button.onClick.AddListener(() => game.CastAbility(ability));
    }

    private void CreateMinimap(RectTransform parent)
    {
        GameObject map = MakePanel(parent, "Minimap", new Color(.025f, .06f, .055f, .94f), new Vector2(.81f, .705f), new Vector2(.985f, .865f), Vector2.zero, Vector2.zero);
        map.GetComponent<Image>().color = new Color(.025f, .06f, .055f, .94f);
        minimapRoot = map.GetComponent<RectTransform>();
        MakeImage(minimapRoot, "Map Lane", new Color(.61f, .57f, .43f, .9f), new Vector2(.12f, .46f), new Vector2(.88f, .54f), Vector2.zero, Vector2.zero).rectTransform.localRotation = Quaternion.Euler(0, 0, 45);
        MakeImage(minimapRoot, "Map River", new Color(.08f, .37f, .4f, .75f), new Vector2(.12f, .5f), new Vector2(.88f, .55f), Vector2.zero, Vector2.zero).rectTransform.localRotation = Quaternion.Euler(0, 0, -45);
        AddMapDot(minimapRoot, "Blue Base", new Vector2(.14f, .14f), new Color(.43f, .85f, .86f), 13);
        AddMapDot(minimapRoot, "Blue Tower", new Vector2(.37f, .37f), new Color(.37f, .74f, .78f), 9);
        AddMapDot(minimapRoot, "Red Tower", new Vector2(.63f, .63f), new Color(.86f, .43f, .38f), 9);
        AddMapDot(minimapRoot, "Red Base", new Vector2(.86f, .86f), new Color(.94f, .46f, .39f), 13);
        AddMapDot(minimapRoot, "Neutral", new Vector2(.52f, .4f), new Color(.82f, .66f, .91f), 8);
        playerMarker = AddMapDot(minimapRoot, "Player Marker", new Vector2(.37f, .37f), Color.white, 11).rectTransform;
        enemyMarker = AddMapDot(minimapRoot, "Enemy Marker", new Vector2(.63f, .63f), new Color(1f, .54f, .4f), 9).rectTransform;
        MakeText(minimapRoot, "Map Label", "АРЕНА", 8, FontStyle.Bold, TextMuted, TextAnchor.UpperLeft,
            new Vector2(.04f, .84f), new Vector2(.35f, .98f), Vector2.zero, Vector2.zero);
    }

    private Image AddMapDot(RectTransform parent, string name, Vector2 anchor, Color color, float size)
    {
        Image dot = MakeImage(parent, name, color, anchor, anchor, new Vector2(-size * .5f, -size * .5f), new Vector2(size * .5f, size * .5f));
        dot.sprite = CreateCircleSprite();
        dot.preserveAspect = true;
        return dot;
    }

    private void BuildPause()
    {
        pausePanel = MakePanel(canvas.transform, "Pause Menu", new Color(.02f, .04f, .045f, .84f), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform root = pausePanel.GetComponent<RectTransform>();
        MakeImage(root, "Pause Card", Panel, new Vector2(.32f, .21f), new Vector2(.68f, .8f), Vector2.zero, Vector2.zero);
        MakeText(root, "Pause Title", "ПАУЗА", 30, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.35f, .66f), new Vector2(.65f, .77f), Vector2.zero, Vector2.zero);
        CreateButton(root, "Resume", "ПРОДОЛЖИТЬ", new Vector2(.37f, .53f), new Vector2(.63f, .63f), game.ResumeMatch, new Color(.43f, .59f, .43f), 16);
        CreateButton(root, "Restart", "ЗАНОВО", new Vector2(.37f, .4f), new Vector2(.63f, .5f), RestartMatch, PanelSoft, 16);
        CreateButton(root, "Pause Lobby", "В МЕНЮ", new Vector2(.37f, .27f), new Vector2(.63f, .37f), game.ReturnToLobby, PanelSoft, 16);
    }

    private void BuildResult()
    {
        resultPanel = MakePanel(canvas.transform, "Match Result", new Color(.02f, .04f, .045f, .89f), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform root = resultPanel.GetComponent<RectTransform>();
        MakeImage(root, "Result Card", Panel, new Vector2(.25f, .17f), new Vector2(.75f, .83f), Vector2.zero, Vector2.zero);
        MakeText(root, "Result Title", "ПОБЕДА", 34, FontStyle.Bold, Gold, TextAnchor.MiddleCenter,
            new Vector2(.3f, .68f), new Vector2(.7f, .8f), Vector2.zero, Vector2.zero);
        MakeText(root, "Result Subtitle", "Вражеское ядро погасло. Разлом под защитой.", 15, FontStyle.Normal, TextMuted, TextAnchor.MiddleCenter,
            new Vector2(.3f, .58f), new Vector2(.7f, .68f), Vector2.zero, Vector2.zero);
        CreateButton(root, "Result Profile", "ПРОФИЛЬ И МЕДАЛИ", new Vector2(.365f, .41f), new Vector2(.635f, .51f), ShowProfile, PanelSoft, 15);
        CreateButton(root, "Result Lobby", "ВЕРНУТЬСЯ В МЕНЮ", new Vector2(.365f, .27f), new Vector2(.635f, .37f), game.ReturnToLobby, new Color(.57f, .4f, .25f), 15);
    }

    private void BuildModal()
    {
        modalOverlay = MakePanel(canvas.transform, "Modal Overlay", new Color(.01f, .025f, .028f, .78f), Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
        RectTransform overlay = modalOverlay.GetComponent<RectTransform>();
        MakeImage(overlay, "Modal Card", Panel, new Vector2(.16f, .11f), new Vector2(.84f, .89f), Vector2.zero, Vector2.zero);
        MakeText(overlay, "Modal Brand", "СУМЕРЕЧНЫЙ РАЗЛОМ", 16, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.195f, .82f), new Vector2(.65f, .88f), Vector2.zero, Vector2.zero);
        CreateButton(overlay, "Close Modal", "×", new Vector2(.78f, .82f), new Vector2(.82f, .88f), CloseModal, new Color(.31f, .19f, .18f), 21);
        modalContent = MakeRect(overlay, new Vector2(.195f, .16f), new Vector2(.805f, .8f), Vector2.zero, Vector2.zero);
    }

    public void ShowLobby()
    {
        SetScreens(lobbyPanel);
        if (modalOverlay != null) modalOverlay.SetActive(false);
        if (game != null) game.SetShowcaseVisible(true);
        RefreshHeroSelection();
    }

    public void ShowLoading(float progress, string stage)
    {
        SetScreens(loadingPanel);
        loadingStageText.text = stage;
        RectTransform fill = loadingProgress.rectTransform;
        fill.anchorMax = new Vector2(.31f + .38f * Mathf.Clamp01(progress), fill.anchorMax.y);
    }

    public void ShowHud()
    {
        SetScreens(hudPanel);
        if (modalOverlay != null) modalOverlay.SetActive(false);
        RefreshHud();
    }

    public void ShowPaused()
    {
        SetScreens(hudPanel, pausePanel);
    }

    public void ShowResult(bool victory, RiftProfileData profile)
    {
        SetScreens(resultPanel);
        Text title = resultPanel.transform.Find("Result Title").GetComponent<Text>();
        Text subtitle = resultPanel.transform.Find("Result Subtitle").GetComponent<Text>();
        title.text = victory ? "ПОБЕДА" : "ПОРАЖЕНИЕ";
        title.color = victory ? Gold : new Color(.9f, .49f, .4f);
        subtitle.text = (victory ? "Вражеское ядро погасло." : "Союзное ядро разрушено.") + "  ·  " + profile.matches + " матчей в профиле";
    }

    private void SetScreens(params GameObject[] active)
    {
        if (lobbyPanel != null) lobbyPanel.SetActive(false);
        if (loadingPanel != null) loadingPanel.SetActive(false);
        if (hudPanel != null) hudPanel.SetActive(false);
        if (pausePanel != null) pausePanel.SetActive(false);
        if (resultPanel != null) resultPanel.SetActive(false);
        if (modalOverlay != null) modalOverlay.SetActive(false);
        foreach (GameObject screen in active) if (screen != null) screen.SetActive(true);
    }

    public void RefreshHeroSelection()
    {
        if (game == null || heroNameText == null) return;
        HeroDefinition hero = game.SelectedHero;
        heroNameText.text = hero.displayName.ToUpperInvariant();
        heroMetaText.text = hero.role + "  ·  " + hero.difficulty + " СЛОЖНОСТЬ";
        heroIntroText.text = hero.epithet + "\n" + hero.intro;
        string skin = hero.skinNames != null && hero.skinNames.Length > 0 ? hero.skinNames[game.SelectedSkin % hero.skinNames.Length] : "Стандартный";
        skinText.text = "ОБЛИК · " + skin.ToUpperInvariant();
        Transform stats = lobbyPanel.transform.Find("Champion Stats Values");
        if (stats != null) stats.GetComponent<Text>().text = string.Format("{0:0}             {1:0}          {2:0}             {3:0.0}", hero.maxHealth, hero.maxMana, hero.basicDamage, hero.moveSpeed);
    }

    public void RefreshHud()
    {
        if (game == null || game.Player == null) return;
        UnitActor player = game.Player;
        if (healthFill != null) healthFill.fillAmount = player.HealthRatio;
        if (manaFill != null) manaFill.fillAmount = player.ManaRatio;
        if (healthText != null) healthText.text = string.Format("ЗДОРОВЬЕ  {0:0} / {1:0}", player.health, player.maxHealth);
        if (manaText != null) manaText.text = string.Format("МАНА  {0:0} / {1:0}", player.mana, player.maxMana);
        if (levelText != null) levelText.text = "УРОВЕНЬ " + player.level + "  ·  " + player.Experience + "/" + player.ExperienceForNextLevel + " XP";
        if (goldText != null) goldText.text = "◈ " + game.Gold;
        if (scoreText != null) scoreText.text = game.Kills + " / " + game.Deaths + " / " + game.Assists;
        if (timeText != null) timeText.text = FormatTime(game.MatchTime);
        if (objectiveText != null) objectiveText.text = objectiveValue;
        for (int i = 0; i < abilityCooldownTexts.Length; i++)
        {
            if (abilityCooldownTexts[i] == null || game.SelectedHero.abilities.Length <= i) continue;
            float cooldown = game.GetAbilityCooldown(i);
            if (abilityKeyTexts[i] != null) abilityKeyTexts[i].text = game.SelectedHero.abilities[i].key;
            abilityCooldownTexts[i].text = cooldown > .05f ? Mathf.CeilToInt(cooldown).ToString() : game.SelectedHero.abilities[i].displayName;
            if (abilityButtons[i] != null) abilityButtons[i].interactable = cooldown <= .05f && player.mana >= game.SelectedHero.abilities[i].manaCost;
        }
        if (minimapRoot != null)
        {
            if (playerMarker != null) PositionMapMarker(playerMarker, player.transform.position);
            UnitActor enemy = game.EnemyHero;
            if (enemyMarker != null)
            {
                enemyMarker.gameObject.SetActive(enemy != null && enemy.IsAlive);
                if (enemy != null && enemy.IsAlive) PositionMapMarker(enemyMarker, enemy.transform.position);
            }
        }
    }

    public void SetObjective(string message)
    {
        objectiveValue = message ?? string.Empty;
        if (objectiveText != null) objectiveText.text = objectiveValue;
    }

    public void SetBuff(bool active)
    {
        if (buffText != null) buffText.gameObject.SetActive(active);
    }

    public void SetRespawn(bool active, int seconds)
    {
        if (respawnOverlay == null) return;
        respawnCountdown = seconds;
        respawnOverlay.SetActive(active);
        if (active && respawnText != null) respawnText.text = "ВОЗВРАЩЕНИЕ ЧЕРЕЗ " + seconds;
    }

    private void Update()
    {
        if (respawnOverlay != null && respawnOverlay.activeSelf && respawnCountdown > 0)
        {
            respawnCountdown = Mathf.Max(0, respawnCountdown - Time.deltaTime);
            if (respawnText != null) respawnText.text = "ВОЗВРАЩЕНИЕ ЧЕРЕЗ " + Mathf.CeilToInt(respawnCountdown);
        }
    }

    private void PositionMapMarker(RectTransform marker, Vector3 world)
    {
        Vector2 p = new Vector2(Mathf.InverseLerp(-14f, 14f, world.x), Mathf.InverseLerp(-14f, 14f, world.z));
        marker.anchorMin = p;
        marker.anchorMax = p;
    }

    private void ShowProfile()
    {
        OpenModal("ЛИЧНЫЙ ПРОФИЛЬ");
        RiftProfileData profile = game.Profile;
        MakeText(modalContent, "Profile Stats", string.Format("{0}     ·     УРОВЕНЬ {1}\n{2} матчей     {3} побед     {4}% побед\nK/D/A   {5} / {6} / {7}\nОПЫТ   {8} XP",
            profile.username, profile.level, profile.matches, profile.wins, profile.WinRate, profile.kills, profile.deaths, profile.assists, profile.xp),
            16, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft, new Vector2(.02f, .67f), new Vector2(.98f, .8f), Vector2.zero, Vector2.zero);
        string medalText = "МЕДАЛИ\n";
        if (profile.medals == null || profile.medals.Count == 0) medalText += "Пока нет наград — сыграй первую тренировку.";
        else
        {
            for (int i = 0; i < profile.medals.Count; i++) medalText += "✦  " + MedalName(profile.medals[i]) + (i % 2 == 0 ? "       " : "\n");
        }
        MakeText(modalContent, "Medal List", medalText, 13, FontStyle.Normal, Gold, TextAnchor.UpperLeft,
            new Vector2(.02f, .47f), new Vector2(.98f, .66f), Vector2.zero, Vector2.zero);
        MakeText(modalContent, "Account Label", game.Api.IsConnected ? "АККАУНТ ПОДКЛЮЧЁН" : "ЛОКАЛЬНЫЙ ПРОФИЛЬ · ДЛЯ СИНХРОНИЗАЦИИ НУЖЕН HTTPS API",
            11, FontStyle.Bold, game.Api.IsConnected ? Cyan : TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.02f, .42f), new Vector2(.98f, .47f), Vector2.zero, Vector2.zero);
        InputField user = CreateInput(modalContent, "Profile Username", "Имя игрока", profile.username, false,
            new Vector2(.02f, .32f), new Vector2(.49f, .405f));
        InputField password = CreateInput(modalContent, "Profile Password", "Пароль (не менее 8 символов)", "", true,
            new Vector2(.51f, .32f), new Vector2(.98f, .405f));
        CreateButton(modalContent, "Register Account", "РЕГИСТРАЦИЯ", new Vector2(.02f, .21f), new Vector2(.32f, .3f),
            () => game.Api.Register(user.text, password.text, OnAuthCompleted), new Color(.42f, .57f, .43f), 13);
        CreateButton(modalContent, "Login Account", "ВОЙТИ", new Vector2(.35f, .21f), new Vector2(.57f, .3f),
            () => game.Api.Login(user.text, password.text, OnAuthCompleted), PanelSoft, 13);
        CreateButton(modalContent, "Sync Profile", "СИНХРОНИЗИРОВАТЬ", new Vector2(.6f, .21f), new Vector2(.98f, .3f), SyncProfile, PanelSoft, 13);
        CreateButton(modalContent, "Logout Account", "ВЫЙТИ ИЗ АККАУНТА", new Vector2(.02f, .1f), new Vector2(.32f, .19f),
            () => game.Api.Logout((ok, text) => { authStatus = text; ShowProfile(); }), new Color(.35f, .22f, .2f), 12);
        MakeText(modalContent, "Auth Status", authStatus, 12, FontStyle.Normal, Cyan, TextAnchor.MiddleLeft,
            new Vector2(.35f, .1f), new Vector2(.98f, .19f), Vector2.zero, Vector2.zero);
    }

    private void ShowSettings()
    {
        OpenModal("НАСТРОЙКИ");
        MakeText(modalContent, "Quality Label", "КАЧЕСТВО ГРАФИКИ", 14, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.02f, .78f), new Vector2(.45f, .86f), Vector2.zero, Vector2.zero);
        string[] quality = { "ЭКОНОМ", "СБАЛАНСИРОВАННО", "ВЫСОКОЕ" };
        for (int i = 0; i < quality.Length; i++)
        {
            int selected = i;
            Color current = RiftSettingsStore.Quality == i ? new Color(.42f, .57f, .43f) : PanelSoft;
            CreateButton(modalContent, "Quality " + i, quality[i], new Vector2(.02f + i * .325f, .69f), new Vector2(.33f + i * .325f, .77f),
                () => { game.SetQuality(selected); ShowSettings(); }, current, 12);
        }
        MakeText(modalContent, "Audio Label", "ЗВУК И МУЗЫКА", 14, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.02f, .59f), new Vector2(.45f, .67f), Vector2.zero, Vector2.zero);
        MakeText(modalContent, "Music Label", "ГРОМКОСТЬ МУЗЫКИ", 11, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.02f, .51f), new Vector2(.32f, .57f), Vector2.zero, Vector2.zero);
        CreateSlider(modalContent, "Music Slider", RiftSettingsStore.Music, new Vector2(.34f, .525f), new Vector2(.68f, .565f), RiftSettingsStore.SetMusic);
        MakeText(modalContent, "Effects Label", "ГРОМКОСТЬ ЭФФЕКТОВ", 11, FontStyle.Bold, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.02f, .43f), new Vector2(.32f, .49f), Vector2.zero, Vector2.zero);
        CreateSlider(modalContent, "Effects Slider", RiftSettingsStore.Effects, new Vector2(.34f, .445f), new Vector2(.68f, .485f), RiftSettingsStore.SetEffects);
        CreateButton(modalContent, "Voice Toggle", "ГОЛОС ГЕРОЯ · " + (RiftSettingsStore.Voice ? "ВКЛ" : "ВЫКЛ"),
            new Vector2(.71f, .435f), new Vector2(.98f, .535f), ToggleVoice, RiftSettingsStore.Voice ? new Color(.42f, .57f, .43f) : PanelSoft, 12);
        MakeText(modalContent, "API Label", "АДРЕС СЕРВЕРА ПРОФИЛЯ (ТОЛЬКО HTTPS)", 12, FontStyle.Bold, Gold, TextAnchor.MiddleLeft,
            new Vector2(.02f, .32f), new Vector2(.65f, .4f), Vector2.zero, Vector2.zero);
        InputField apiInput = CreateInput(modalContent, "API URL", "https://example.com", game.Api.BaseUrl, false,
            new Vector2(.02f, .22f), new Vector2(.75f, .31f));
        CreateButton(modalContent, "Save API", "СОХРАНИТЬ", new Vector2(.78f, .22f), new Vector2(.98f, .31f),
            () => { game.Api.SetBaseUrl(apiInput.text); authStatus = "Адрес API сохранён."; ShowSettings(); }, new Color(.42f, .57f, .43f), 12);
        MakeText(modalContent, "Settings Note", "Голосовые реплики используют системный русский синтез речи Android.\nПостоянный сервер профиля настраивается отдельно; локальный матч доступен офлайн.",
            11, FontStyle.Normal, TextMuted, TextAnchor.MiddleLeft, new Vector2(.02f, .08f), new Vector2(.98f, .18f), Vector2.zero, Vector2.zero);
    }

    private void ShowRoster()
    {
        OpenModal("ГЕРОИ РАЗЛОМА");
        IReadOnlyList<HeroDefinition> roster = HeroDefinition.Catalog.All;
        float rowHeight = .24f;
        for (int i = 0; i < roster.Count; i++)
        {
            HeroDefinition hero = roster[i];
            float top = .92f - i * rowHeight;
            MakeImage(modalContent, "Roster Card " + i, PanelSoft, new Vector2(.02f, top - .2f), new Vector2(.98f, top), Vector2.zero, Vector2.zero);
            MakeText(modalContent, "Roster Hero " + i, hero.displayName + "  ·  " + hero.role + "  ·  " + hero.epithet + "\n" + hero.intro,
                13, FontStyle.Normal, TextPrimary, TextAnchor.MiddleLeft, new Vector2(.05f, top - .19f), new Vector2(.72f, top - .01f), Vector2.zero, Vector2.zero);
            CreateButton(modalContent, "Select Roster Hero " + i, "ВЫБРАТЬ", new Vector2(.78f, top - .15f), new Vector2(.95f, top - .05f),
                () => { while (game.SelectedHero.heroId != hero.heroId) game.SelectHero(1); CloseModal(); }, new Color(.42f, .57f, .43f), 12);
        }
        MakeText(modalContent, "Roster Footer", "ТРИ ОРИГИНАЛЬНЫХ ГЕРОЯ · РАЗНЫЕ РОЛИ, ХАРАКТЕРИСТИКИ И КОМПЛЕКТЫ СПОСОБНОСТЕЙ",
            11, FontStyle.Bold, Gold, TextAnchor.MiddleCenter, new Vector2(.03f, .12f), new Vector2(.97f, .22f), Vector2.zero, Vector2.zero);
    }

    private void OpenModal(string heading)
    {
        if (modalOverlay == null) return;
        modalOverlay.SetActive(true);
        Transform brand = modalOverlay.transform.Find("Modal Brand");
        if (brand != null) brand.GetComponent<Text>().text = heading;
        for (int i = modalContent.childCount - 1; i >= 0; i--) Destroy(modalContent.GetChild(i).gameObject);
    }

    private void CloseModal()
    {
        if (modalOverlay != null) modalOverlay.SetActive(false);
        authStatus = string.Empty;
    }

    private void OnAuthCompleted(bool success, string message)
    {
        authStatus = message;
        if (success) game.Api.FetchProfile((ok, text, profile) =>
        {
            authStatus = ok ? "Профиль и статистика синхронизированы." : text;
            if (ok && profile != null) RiftProfileStore.Save(profile);
            ShowProfile();
        });
        else ShowProfile();
    }

    private void SyncProfile()
    {
        game.Api.FetchProfile((success, message, profile) =>
        {
            authStatus = message;
            if (success && profile != null) RiftProfileStore.Save(profile);
            ShowProfile();
        });
    }

    private void ToggleVoice()
    {
        RiftSettingsStore.SetVoice(!RiftSettingsStore.Voice);
        ShowSettings();
    }

    private void RestartMatch()
    {
        game.ReturnToLobby();
        game.StartMatch();
    }

    private InputField CreateInput(RectTransform parent, string name, string placeholderText, string initialValue, bool password, Vector2 min, Vector2 max)
    {
        GameObject fieldObject = MakePanel(parent, name, new Color(.035f, .06f, .06f, 1), min, max, Vector2.zero, Vector2.zero);
        InputField field = fieldObject.AddComponent<InputField>();
        Text text = MakeText(fieldObject.transform, name + " Text", "", 14, FontStyle.Normal, TextPrimary, TextAnchor.MiddleLeft,
            new Vector2(.035f, .05f), new Vector2(.965f, .95f), Vector2.zero, Vector2.zero);
        Text placeholder = MakeText(fieldObject.transform, name + " Placeholder", placeholderText, 13, FontStyle.Italic, TextMuted, TextAnchor.MiddleLeft,
            new Vector2(.035f, .05f), new Vector2(.965f, .95f), Vector2.zero, Vector2.zero);
        field.textComponent = text;
        field.placeholder = placeholder;
        field.text = initialValue ?? string.Empty;
        field.lineType = InputField.LineType.SingleLine;
        field.contentType = password ? InputField.ContentType.Password : InputField.ContentType.Standard;
        field.characterLimit = password ? 128 : 160;
        return field;
    }

    private void CreateSlider(RectTransform parent, string name, float value, Vector2 min, Vector2 max, Action<float> changed)
    {
        GameObject sliderObject = MakePanel(parent, name, new Color(.04f, .07f, .07f, 1), min, max, Vector2.zero, Vector2.zero);
        Slider slider = sliderObject.AddComponent<Slider>();
        GameObject fillArea = MakePanel(sliderObject.transform, "Fill Area", new Color(0, 0, 0, 0), new Vector2(.02f, .18f), new Vector2(.98f, .82f), Vector2.zero, Vector2.zero);
        Image fill = MakeImage(fillArea.transform, "Fill", Gold, new Vector2(0, 0), new Vector2(Mathf.Clamp01(value), 1), Vector2.zero, Vector2.zero);
        GameObject handleArea = new GameObject("Handle Slide Area", typeof(RectTransform));
        handleArea.transform.SetParent(sliderObject.transform, false);
        RectTransform handleRect = handleArea.GetComponent<RectTransform>();
        handleRect.anchorMin = new Vector2(.02f, .05f);
        handleRect.anchorMax = new Vector2(.98f, .95f);
        GameObject handle = MakePanel(handleArea.transform, "Handle", TextPrimary, new Vector2(value, .05f), new Vector2(value, .95f), new Vector2(-8, 0), new Vector2(8, 0));
        slider.fillRect = fill.rectTransform;
        slider.handleRect = handle.GetComponent<RectTransform>();
        slider.targetGraphic = handle.GetComponent<Image>();
        slider.direction = Slider.Direction.LeftToRight;
        slider.minValue = 0;
        slider.maxValue = 1;
        slider.value = value;
        slider.onValueChanged.AddListener(v => changed(v));
    }

    private Button CreateButton(RectTransform parent, string name, string label, Vector2 min, Vector2 max, Action click, Color color, int fontSize)
    {
        GameObject buttonObject = MakePanel(parent, name, color, min, max, Vector2.zero, Vector2.zero);
        Button button = buttonObject.AddComponent<Button>();
        button.targetGraphic = buttonObject.GetComponent<Image>();
        ColorBlock colors = button.colors;
        colors.normalColor = color;
        colors.highlightedColor = Color.Lerp(color, Color.white, .18f);
        colors.pressedColor = Color.Lerp(color, Color.black, .14f);
        colors.selectedColor = Color.Lerp(color, Color.white, .1f);
        button.colors = colors;
        button.onClick.AddListener(() => { if (click != null) click(); });
        MakeText(buttonObject.transform, name + " Label", label, fontSize, FontStyle.Bold, TextPrimary, TextAnchor.MiddleCenter,
            new Vector2(.04f, .04f), new Vector2(.96f, .96f), Vector2.zero, Vector2.zero);
        return button;
    }

    private GameObject MakePanel(Transform parent, string name, Color color, Vector2 min, Vector2 max, Vector2 offsetMin, Vector2 offsetMax)
    {
        GameObject panel = new GameObject(name, typeof(RectTransform), typeof(CanvasRenderer), typeof(Image));
        panel.transform.SetParent(parent, false);
        RectTransform target = panel.GetComponent<RectTransform>();
        target.anchorMin = min;
        target.anchorMax = max;
        target.offsetMin = offsetMin;
        target.offsetMax = offsetMax;
        panel.GetComponent<Image>().color = color;
        return panel;
    }

    private Image MakeImage(Transform parent, string name, Color color, Vector2 min, Vector2 max, Vector2 offsetMin, Vector2 offsetMax)
    {
        GameObject imageObject = new GameObject(name, typeof(RectTransform), typeof(CanvasRenderer), typeof(Image));
        imageObject.transform.SetParent(parent, false);
        RectTransform rect = imageObject.GetComponent<RectTransform>();
        rect.anchorMin = min;
        rect.anchorMax = max;
        rect.offsetMin = offsetMin;
        rect.offsetMax = offsetMax;
        Image image = imageObject.GetComponent<Image>();
        image.color = color;
        return image;
    }

    private Text MakeText(Transform parent, string name, string value, int size, FontStyle style, Color color, TextAnchor anchor,
        Vector2 min, Vector2 max, Vector2 offsetMin, Vector2 offsetMax)
    {
        GameObject textObject = new GameObject(name, typeof(RectTransform), typeof(CanvasRenderer), typeof(Text));
        textObject.transform.SetParent(parent, false);
        RectTransform rect = textObject.GetComponent<RectTransform>();
        rect.anchorMin = min;
        rect.anchorMax = max;
        rect.offsetMin = offsetMin;
        rect.offsetMax = offsetMax;
        Text text = textObject.GetComponent<Text>();
        text.font = font;
        text.text = value;
        text.fontSize = size;
        text.fontStyle = style;
        text.color = color;
        text.alignment = anchor;
        text.horizontalOverflow = HorizontalWrapMode.Wrap;
        text.verticalOverflow = VerticalWrapMode.Overflow;
        text.raycastTarget = false;
        return text;
    }

    private RectTransform MakeRect(Transform parent, Vector2 min, Vector2 max, Vector2 offsetMin, Vector2 offsetMax)
    {
        GameObject obj = new GameObject("Modal Content", typeof(RectTransform));
        obj.transform.SetParent(parent, false);
        RectTransform rect = obj.GetComponent<RectTransform>();
        rect.anchorMin = min;
        rect.anchorMax = max;
        rect.offsetMin = offsetMin;
        rect.offsetMax = offsetMax;
        return rect;
    }

    private static Sprite CreateCircleSprite()
    {
        if (circleSprite != null) return circleSprite;
        const int size = 64;
        Texture2D texture = new Texture2D(size, size, TextureFormat.RGBA32, false);
        Color[] pixels = new Color[size * size];
        Vector2 center = new Vector2((size - 1) * .5f, (size - 1) * .5f);
        float radius = size * .47f;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
            {
                float distance = Vector2.Distance(new Vector2(x, y), center);
                float alpha = Mathf.Clamp01(radius - distance + .8f);
                pixels[y * size + x] = new Color(1, 1, 1, alpha);
            }
        texture.SetPixels(pixels);
        texture.Apply();
        circleSprite = Sprite.Create(texture, new Rect(0, 0, size, size), new Vector2(.5f, .5f), 100);
        return circleSprite;
    }

    private static string FormatTime(float seconds)
    {
        int total = Mathf.FloorToInt(seconds);
        return (total / 60).ToString("00") + ":" + (total % 60).ToString("00");
    }

    private static string MedalName(string id)
    {
        switch (id)
        {
            case "first_match": return "Первый шаг";
            case "first_win": return "Первая победа";
            case "slayer": return "Охотник";
            case "unbroken": return "Несокрушимый";
            case "veteran": return "Ветеран";
            case "champion": return "Чемпион";
            default: return id;
        }
    }
}
