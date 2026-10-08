#if UNITY_EDITOR
using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

[InitializeOnLoad]
public static class RiftArenaProjectSetup
{
    private const string MainScenePath = "Assets/Scenes/RiftArenaMain.unity";
    private static string ProjectRoot => Path.GetFullPath(Path.Combine(Application.dataPath, ".."));
    private static string MainSceneAbsolutePath => Path.Combine(ProjectRoot, MainScenePath.Replace('/', Path.DirectorySeparatorChar));

    static RiftArenaProjectSetup()
    {
        EditorApplication.delayCall += EnsureDefaultScene;
    }

    [MenuItem("Rift Arena/Build Android APK")]
    public static void PrepareAndroidBuild()
    {
        EnsureDefaultScene();
        ConfigureAndroidPlayer();
        EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
        string output = GetArgument("-riftOutput", Path.GetFullPath(Path.Combine(ProjectRoot, "../../dist/RiftArena-Unity-0.3.0.apk")));
        output = Path.GetFullPath(output);
        Directory.CreateDirectory(Path.GetDirectoryName(output));
        string[] scenes = { MainScenePath };
        var options = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = output,
            target = BuildTarget.Android,
            options = BuildOptions.None
        };
        BuildReport report = BuildPipeline.BuildPlayer(options);
        if (report.summary.result != BuildResult.Succeeded)
            throw new Exception("Rift Arena Android build failed: " + report.summary.result + " (" + report.summary.totalErrors + " errors). Check the Unity Editor log.");
        Debug.Log("Rift Arena APK created: " + output + " (" + report.summary.totalSize + " bytes)");
    }

    [MenuItem("Rift Arena/Create or repair boot scene")]
    public static void EnsureDefaultScene()
    {
        Directory.CreateDirectory(Path.Combine(Application.dataPath, "Scenes"));
        if (!File.Exists(MainSceneAbsolutePath))
        {
            Scene scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            GameObject marker = new GameObject("Rift Arena Runtime Entry");
            marker.transform.position = Vector3.zero;
            EditorSceneManager.MarkSceneDirty(scene);
            EditorSceneManager.SaveScene(scene, MainScenePath);
            Debug.Log("Created runtime-bootstrap scene: " + MainScenePath + " (" + marker.name + ")");
        }
        EditorBuildSettingsScene[] current = EditorBuildSettings.scenes;
        bool included = false;
        foreach (EditorBuildSettingsScene item in current)
            if (item.path == MainScenePath && item.enabled) included = true;
        if (!included)
        {
            var scenes = new EditorBuildSettingsScene[current.Length + 1];
            Array.Copy(current, scenes, current.Length);
            scenes[current.Length] = new EditorBuildSettingsScene(MainScenePath, true);
            EditorBuildSettings.scenes = scenes;
        }
    }

    private static void ConfigureAndroidPlayer()
    {
        PlayerSettings.companyName = "Modar35";
        PlayerSettings.productName = "Сумеречный разлом";
        PlayerSettings.bundleVersion = "0.3.0";
        PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, "com.modar.riftarena");
        PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;
        PlayerSettings.defaultScreenWidth = 1920;
        PlayerSettings.defaultScreenHeight = 1080;
        PlayerSettings.runInBackground = false;
        PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
        PlayerSettings.SetApiCompatibilityLevel(BuildTargetGroup.Android, ApiCompatibilityLevel.NET_Standard_2_1);
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel24;
        PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64 | AndroidArchitecture.ARMv7;
        PlayerSettings.Android.forceInternetPermission = true;
        PlayerSettings.stripEngineCode = true;
        PlayerSettings.SetManagedStrippingLevel(BuildTargetGroup.Android, ManagedStrippingLevel.Low);
    }

    private static string GetArgument(string key, string fallback)
    {
        string[] args = Environment.GetCommandLineArgs();
        string prefix = key + "=";
        foreach (string arg in args)
            if (arg.StartsWith(prefix, StringComparison.Ordinal)) return arg.Substring(prefix.Length).Trim('"');
        return fallback;
    }
}
#endif
