using System;
using System.Collections;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.Networking;

public sealed class ProfileApiClient : MonoBehaviour
{
    private const string ApiUrlKey = "rift.unity.api.url";
    private const string TokenKey = "rift.unity.api.token";
    public string BaseUrl { get; private set; }
    public string Token { get; private set; }
    public bool IsConnected => !string.IsNullOrEmpty(Token);

    [Serializable] private sealed class AuthBody { public string username; public string password; }
    [Serializable] private sealed class MatchBody { public string result; public int kills; public int deaths; public int assists; public int duration; public int gold; public string hero; }
    [Serializable] public sealed class ApiMedal { public string id; public string name; public string description; public long earnedAt; }
    [Serializable] public sealed class ApiProfile
    {
        public string username;
        public int level;
        public int xp;
        public int nextLevelXp;
        public int matches;
        public int wins;
        public int losses;
        public int winRate;
        public int kills;
        public int deaths;
        public int assists;
        public ApiMedal[] medals;

        public RiftProfileData ToLocal()
        {
            var result = new RiftProfileData
            {
                username = username,
                level = level,
                xp = xp,
                matches = matches,
                wins = wins,
                losses = losses,
                kills = kills,
                deaths = deaths,
                assists = assists,
                medals = new List<string>()
            };
            if (medals != null)
                foreach (ApiMedal medal in medals)
                    if (medal != null && !string.IsNullOrEmpty(medal.id)) result.medals.Add(medal.id);
            return result;
        }
    }
    [Serializable] public sealed class ApiReply { public bool ok; public string token; public string error; public ApiProfile profile; public int xpEarned; public string[] newMedals; }

    private void Awake()
    {
        BaseUrl = PlayerPrefs.GetString(ApiUrlKey, string.Empty).Trim().TrimEnd('/');
        Token = PlayerPrefs.GetString(TokenKey, string.Empty);
    }

    public void SetBaseUrl(string value)
    {
        BaseUrl = (value ?? string.Empty).Trim().TrimEnd('/');
        PlayerPrefs.SetString(ApiUrlKey, BaseUrl);
        PlayerPrefs.Save();
    }

    public void Register(string username, string password, Action<bool, string> completed)
    {
        StartCoroutine(AuthRequest("/api/register", username, password, completed));
    }

    public void Login(string username, string password, Action<bool, string> completed)
    {
        StartCoroutine(AuthRequest("/api/login", username, password, completed));
    }

    public void Logout(Action<bool, string> completed = null)
    {
        if (string.IsNullOrEmpty(Token))
        {
            ClearToken();
            completed?.Invoke(true, "Локальный выход выполнен.");
            return;
        }
        StartCoroutine(Send("/api/logout", "{}", true, (success, body, error) =>
        {
            ClearToken();
            completed?.Invoke(success, success ? "Вы вышли из аккаунта." : error);
        }));
    }

    public void RecordMatch(bool victory, int kills, int deaths, int assists, int duration, int gold, string heroId, Action<bool, string, RiftProfileData> completed)
    {
        if (string.IsNullOrEmpty(Token))
        {
            completed?.Invoke(false, "Аккаунт не подключён; результат сохранён локально.", null);
            return;
        }
        var body = new MatchBody
        {
            result = victory ? "win" : "loss",
            kills = Mathf.Max(0, kills),
            deaths = Mathf.Max(0, deaths),
            assists = Mathf.Max(0, assists),
            duration = Mathf.Clamp(duration, 0, 7200),
            gold = Mathf.Clamp(gold, 0, 1000000),
            hero = heroId ?? string.Empty
        };
        StartCoroutine(Send("/api/match", JsonUtility.ToJson(body), true, (success, response, error) =>
        {
            ApiReply reply = success ? Parse(response) : null;
            completed?.Invoke(success, success ? "Результат синхронизирован." : error, reply != null && reply.profile != null ? reply.profile.ToLocal() : null);
        }));
    }

    public void FetchProfile(Action<bool, string, RiftProfileData> completed)
    {
        StartCoroutine(Send("/api/profile", null, true, (success, response, error) =>
        {
            ApiReply reply = success ? Parse(response) : null;
            completed?.Invoke(success, success ? "Профиль синхронизирован." : error, reply != null && reply.profile != null ? reply.profile.ToLocal() : null);
        }));
    }

    private IEnumerator AuthRequest(string path, string username, string password, Action<bool, string> completed)
    {
        if (!IsValidHttpsBase())
        {
            completed?.Invoke(false, "Укажите адрес API с HTTPS в настройках.");
            yield break;
        }
        var body = new AuthBody { username = username, password = password };
        yield return Send(path, JsonUtility.ToJson(body), false, (success, response, error) =>
        {
            if (!success)
            {
                completed?.Invoke(false, error);
                return;
            }
            ApiReply reply = Parse(response);
            if (reply == null || string.IsNullOrEmpty(reply.token))
            {
                completed?.Invoke(false, "Сервер вернул неполный ответ.");
                return;
            }
            Token = reply.token;
            PlayerPrefs.SetString(TokenKey, Token);
            PlayerPrefs.Save();
            if (reply.profile != null) RiftProfileStore.Save(reply.profile.ToLocal());
            completed?.Invoke(true, "Аккаунт подключён.");
        });
    }

    private IEnumerator Send(string path, string json, bool authenticated, Action<bool, string, string> completed)
    {
        if (!IsValidHttpsBase())
        {
            completed?.Invoke(false, string.Empty, "Укажите адрес API с HTTPS в настройках.");
            yield break;
        }
        string method = json == null ? UnityWebRequest.kHttpVerbGET : UnityWebRequest.kHttpVerbPOST;
        using (var request = new UnityWebRequest(BaseUrl + path, method))
        {
            request.downloadHandler = new DownloadHandlerBuffer();
            if (json != null)
            {
                byte[] bytes = Encoding.UTF8.GetBytes(json);
                request.uploadHandler = new UploadHandlerRaw(bytes);
                request.SetRequestHeader("Content-Type", "application/json; charset=utf-8");
            }
            if (authenticated && !string.IsNullOrEmpty(Token)) request.SetRequestHeader("Authorization", "Bearer " + Token);
            request.timeout = 12;
            yield return request.SendWebRequest();
            string response = request.downloadHandler != null ? request.downloadHandler.text : string.Empty;
            if (request.result == UnityWebRequest.Result.Success)
            {
                completed?.Invoke(true, response, string.Empty);
            }
            else
            {
                string message = "Сервер не ответил.";
                ApiReply errorReply = Parse(response);
                if (errorReply != null && !string.IsNullOrEmpty(errorReply.error)) message = errorReply.error;
                else if (!string.IsNullOrEmpty(request.error)) message = request.error;
                completed?.Invoke(false, response, message);
            }
        }
    }

    private bool IsValidHttpsBase()
    {
        return !string.IsNullOrEmpty(BaseUrl) && BaseUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
    }

    private void ClearToken()
    {
        Token = string.Empty;
        PlayerPrefs.DeleteKey(TokenKey);
        PlayerPrefs.Save();
    }

    private static ApiReply Parse(string json)
    {
        if (string.IsNullOrEmpty(json)) return null;
        try { return JsonUtility.FromJson<ApiReply>(json); }
        catch (Exception) { return null; }
    }
}
