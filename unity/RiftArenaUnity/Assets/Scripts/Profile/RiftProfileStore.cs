using System;
using System.Collections.Generic;
using UnityEngine;

[Serializable]
public sealed class RiftProfileData
{
    public string username = "Странник";
    public int level = 1;
    public int xp;
    public int matches;
    public int wins;
    public int losses;
    public int kills;
    public int deaths;
    public int assists;
    public List<string> medals = new List<string>();

    public int WinRate => matches <= 0 ? 0 : Mathf.RoundToInt((float)wins / matches * 100f);
}

public static class RiftProfileStore
{
    private const string Key = "rift.unity.profile.v1";

    public static RiftProfileData Load()
    {
        string json = PlayerPrefs.GetString(Key, string.Empty);
        if (string.IsNullOrEmpty(json)) return new RiftProfileData();
        try
        {
            RiftProfileData data = JsonUtility.FromJson<RiftProfileData>(json);
            return data ?? new RiftProfileData();
        }
        catch (Exception)
        {
            return new RiftProfileData();
        }
    }

    public static RiftProfileData RecordMatch(bool victory, int kills, int deaths, int assists)
    {
        RiftProfileData profile = Load();
        profile.matches++;
        if (victory) profile.wins++; else profile.losses++;
        profile.kills += Mathf.Max(0, kills);
        profile.deaths += Mathf.Max(0, deaths);
        profile.assists += Mathf.Max(0, assists);
        profile.xp += 100 + Mathf.Max(0, kills) * 18 + Mathf.Max(0, assists) * 8 + (victory ? 120 : 0);
        profile.level = Mathf.Clamp(profile.xp / 500 + 1, 1, 100);
        AddMedal(profile, "first_match", profile.matches >= 1);
        AddMedal(profile, "first_win", profile.wins >= 1);
        AddMedal(profile, "slayer", kills >= 5);
        AddMedal(profile, "unbroken", victory && deaths <= 1);
        AddMedal(profile, "veteran", profile.matches >= 10);
        AddMedal(profile, "champion", profile.wins >= 5);
        Save(profile);
        return profile;
    }

    public static void Save(RiftProfileData profile)
    {
        if (profile == null) return;
        PlayerPrefs.SetString(Key, JsonUtility.ToJson(profile));
        PlayerPrefs.Save();
    }

    private static void AddMedal(RiftProfileData profile, string id, bool condition)
    {
        if (condition && !profile.medals.Contains(id)) profile.medals.Add(id);
    }
}
