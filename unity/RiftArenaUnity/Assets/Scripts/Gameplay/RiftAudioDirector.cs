using UnityEngine;

[DisallowMultipleComponent]
public sealed class RiftAudioDirector : MonoBehaviour
{
    private const int SampleRate = 22050;
    private AudioSource musicSource;
    private AudioSource effectsSource;
    private AudioClip ambience;
    private AudioClip hitClip;
    private AudioClip spellClip;
    private AudioClip victoryClip;

    private static RiftAudioDirector instance;

    private void Awake()
    {
        instance = this;
        GameObject musicObject = new GameObject("Procedural Dusk Music");
        musicObject.transform.SetParent(transform, false);
        musicSource = musicObject.AddComponent<AudioSource>();
        musicSource.loop = true;
        musicSource.playOnAwake = false;
        musicSource.spatialBlend = 0;
        musicSource.priority = 0;
        ambience = BuildAmbience();
        musicSource.clip = ambience;
        musicSource.volume = RiftSettingsStore.Music;
        if (musicSource.volume > .001f) musicSource.Play();

        GameObject effectObject = new GameObject("Procedural Sound Effects");
        effectObject.transform.SetParent(transform, false);
        effectsSource = effectObject.AddComponent<AudioSource>();
        effectsSource.playOnAwake = false;
        effectsSource.spatialBlend = 0;
        effectsSource.volume = RiftSettingsStore.Effects;
        hitClip = BuildTone("Soft Impact", 145, 82, .19f, .5f);
        spellClip = BuildTone("Rift Shimmer", 420, 880, .38f, .34f);
        victoryClip = BuildTone("Crystal Chime", 330, 660, .9f, .25f);
    }

    public static void SetMusicVolume(float volume)
    {
        if (instance == null || instance.musicSource == null) return;
        instance.musicSource.volume = Mathf.Clamp01(volume);
        if (volume > .001f && !instance.musicSource.isPlaying) instance.musicSource.Play();
        else if (volume <= .001f && instance.musicSource.isPlaying) instance.musicSource.Pause();
    }

    public static void SetEffectsVolume(float volume)
    {
        if (instance != null && instance.effectsSource != null) instance.effectsSource.volume = Mathf.Clamp01(volume);
    }

    public static void SetPaused(bool paused)
    {
        if (instance == null || instance.musicSource == null) return;
        if (paused) instance.musicSource.Pause();
        else if (instance.musicSource.volume > .001f) instance.musicSource.UnPause();
    }

    public static void PlayImpact(float pitch = 1f)
    {
        if (instance == null || instance.effectsSource == null) return;
        instance.effectsSource.pitch = Mathf.Clamp(pitch, .65f, 1.5f);
        instance.effectsSource.PlayOneShot(instance.hitClip, .55f);
    }

    public static void PlaySpell(float pitch = 1f)
    {
        if (instance == null || instance.effectsSource == null) return;
        instance.effectsSource.pitch = Mathf.Clamp(pitch, .65f, 1.5f);
        instance.effectsSource.PlayOneShot(instance.spellClip, .62f);
    }

    public static void PlayVictory()
    {
        if (instance == null || instance.effectsSource == null) return;
        instance.effectsSource.pitch = 1f;
        instance.effectsSource.PlayOneShot(instance.victoryClip, .8f);
    }

    private static AudioClip BuildAmbience()
    {
        const int seconds = 16;
        int length = SampleRate * seconds;
        float[] samples = new float[length * 2];
        float[] roots = { 55f, 58.27f, 65.41f, 49f };
        for (int i = 0; i < length; i++)
        {
            float time = (float)i / SampleRate;
            int chord = Mathf.FloorToInt(time / 4f) % roots.Length;
            float root = roots[chord];
            float blend = Mathf.SmoothStep(0, 1, Mathf.PingPong(time / 4f, 1));
            float next = roots[(chord + 1) % roots.Length];
            float fundamental = Mathf.Lerp(root, next, blend);
            float swell = .45f + .55f * Mathf.Sin(Mathf.PI * Mathf.Clamp01((time % 4f) / 4f));
            float loopFade = Mathf.Min(1f, Mathf.Min(time / 1.4f, (seconds - time) / 1.4f));
            float chordWave = Mathf.Sin(time * fundamental * Mathf.PI * 2f) * .18f
                + Mathf.Sin(time * fundamental * 1.5f * Mathf.PI * 2f) * .105f
                + Mathf.Sin(time * fundamental * 2f * Mathf.PI * 2f) * .055f
                + Mathf.Sin(time * (fundamental * 2.01f + Mathf.Sin(time * .18f) * .35f) * Mathf.PI * 2f) * .028f;
            float tone = Mathf.Clamp(chordWave * swell * loopFade, -.42f, .42f);
            samples[i * 2] = tone;
            samples[i * 2 + 1] = tone * .96f;
        }
        AudioClip clip = AudioClip.Create("Duskwood Ambient", length, 2, SampleRate, false);
        clip.SetData(samples, 0);
        return clip;
    }

    private static AudioClip BuildTone(string name, float low, float high, float duration, float amplitude)
    {
        int length = Mathf.CeilToInt(SampleRate * duration);
        float[] samples = new float[length * 2];
        uint noise = 0x9e3779b9u;
        for (int i = 0; i < length; i++)
        {
            float time = (float)i / SampleRate;
            float progress = time / duration;
            float envelope = Mathf.Pow(1f - progress, name == "Crystal Chime" ? 1.3f : 2.2f);
            float frequency = Mathf.Lerp(low, high, Mathf.SmoothStep(0, 1, progress));
            float wave = Mathf.Sin(time * frequency * Mathf.PI * 2f) * .76f
                + Mathf.Sin(time * frequency * 2.01f * Mathf.PI * 2f) * .16f;
            noise ^= noise << 13;
            noise ^= noise >> 17;
            noise ^= noise << 5;
            float grit = ((noise & 0xffff) / 32767.5f - 1f) * .08f;
            float sample = Mathf.Clamp((wave + grit) * envelope * amplitude, -.9f, .9f);
            samples[i * 2] = sample;
            samples[i * 2 + 1] = sample;
        }
        AudioClip clip = AudioClip.Create(name, length, 2, SampleRate, false);
        clip.SetData(samples, 0);
        return clip;
    }

    private void OnDestroy()
    {
        if (instance == this) instance = null;
        if (ambience != null) Destroy(ambience);
        if (hitClip != null) Destroy(hitClip);
        if (spellClip != null) Destroy(spellClip);
        if (victoryClip != null) Destroy(victoryClip);
    }
}
