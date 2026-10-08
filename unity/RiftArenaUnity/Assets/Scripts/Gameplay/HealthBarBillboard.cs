using UnityEngine;

public sealed class HealthBarBillboard : MonoBehaviour
{
    private static Material backgroundMaterial;
    private static Material blueMaterial;
    private static Material redMaterial;
    private static Material neutralMaterial;

    private RiftTeam team;
    private Transform fill;
    private Camera mainCamera;

    public void Initialize(RiftTeam owner)
    {
        team = owner;
        GameObject background = GameObject.CreatePrimitive(PrimitiveType.Cube);
        background.name = "Bar Background";
        background.transform.SetParent(transform, false);
        background.transform.localScale = Vector3.one;
        Renderer bg = background.GetComponent<Renderer>();
        if (backgroundMaterial == null) backgroundMaterial = CreateMaterial(new Color(.04f, .06f, .07f, .9f));
        bg.sharedMaterial = backgroundMaterial;
        Collider col = background.GetComponent<Collider>();
        if (col != null) Destroy(col);
        GameObject barFill = GameObject.CreatePrimitive(PrimitiveType.Cube);
        barFill.name = "Bar Fill";
        barFill.transform.SetParent(transform, false);
        barFill.transform.localPosition = new Vector3(-.48f, 0, -.08f);
        barFill.transform.localScale = new Vector3(.96f, .8f, .6f);
        Renderer fillRenderer = barFill.GetComponent<Renderer>();
        if (team == RiftTeam.Blue)
        {
            if (blueMaterial == null) blueMaterial = CreateMaterial(new Color(.35f, .83f, .85f));
            fillRenderer.sharedMaterial = blueMaterial;
        }
        else if (team == RiftTeam.Neutral)
        {
            if (neutralMaterial == null) neutralMaterial = CreateMaterial(new Color(.85f, .66f, .38f));
            fillRenderer.sharedMaterial = neutralMaterial;
        }
        else
        {
            if (redMaterial == null) redMaterial = CreateMaterial(new Color(.92f, .4f, .34f));
            fillRenderer.sharedMaterial = redMaterial;
        }
        col = barFill.GetComponent<Collider>();
        if (col != null) Destroy(col);
        fill = barFill.transform;
        mainCamera = Camera.main;
    }

    private static Material CreateMaterial(Color color)
    {
        Shader shader = Shader.Find("Unlit/Color");
        if (shader == null) shader = Shader.Find("Sprites/Default");
        if (shader == null) shader = Shader.Find("Standard");
        return shader == null ? null : new Material(shader) { color = color };
    }

    private void LateUpdate()
    {
        if (mainCamera == null) mainCamera = Camera.main;
        if (mainCamera != null) transform.rotation = Quaternion.LookRotation(mainCamera.transform.position - transform.position, Vector3.up);
        UnitActor actor = GetComponentInParent<UnitActor>();
        if (actor == null || fill == null) return;
        float ratio = actor.HealthRatio;
        fill.localScale = new Vector3(.96f * ratio, .8f, .6f);
        fill.localPosition = new Vector3(-.48f + .48f * ratio, 0, -.08f);
    }
}
