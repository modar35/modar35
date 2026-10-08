using UnityEngine;

public static class RiftSettingsStore
{
    private const string QualityKey = "rift.unity.quality";
    private const string MusicKey = "rift.unity.music";
    private const string EffectsKey = "rift.unity.effects";
    private const string VoiceKey = "rift.unity.voice";

    public static int Quality => Mathf.Clamp(PlayerPrefs.GetInt(QualityKey, 1), 0, 2);
    public static float Music => Mathf.Clamp01(PlayerPrefs.GetFloat(MusicKey, .25f));
    public static float Effects => Mathf.Clamp01(PlayerPrefs.GetFloat(EffectsKey, .65f));
    public static bool Voice => PlayerPrefs.GetInt(VoiceKey, 1) != 0;

    public static void Apply()
    {
        QualitySettings.SetQualityLevel(Quality, true);
        Application.targetFrameRate = Quality == 0 ? 30 : 60;
        AudioListener.volume = 1f;
    }

    public static void SetQuality(int value)
    {
        PlayerPrefs.SetInt(QualityKey, Mathf.Clamp(value, 0, 2));
        PlayerPrefs.Save();
        Apply();
    }

    public static void SetMusic(float value)
    {
        PlayerPrefs.SetFloat(MusicKey, Mathf.Clamp01(value));
        PlayerPrefs.Save();
        RiftAudioDirector.SetMusicVolume(Music);
    }

    public static void SetEffects(float value)
    {
        PlayerPrefs.SetFloat(EffectsKey, Mathf.Clamp01(value));
        PlayerPrefs.Save();
        RiftAudioDirector.SetEffectsVolume(Effects);
        Apply();
    }

    public static void SetVoice(bool value)
    {
        PlayerPrefs.SetInt(VoiceKey, value ? 1 : 0);
        PlayerPrefs.Save();
    }
}
