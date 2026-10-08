using UnityEngine;

public sealed class SimpleVfx : MonoBehaviour
{
    private float radius;
    private float duration;
    private float age;

    public void Initialize(float targetRadius, float life)
    {
        radius = targetRadius;
        duration = Mathf.Max(.05f, life);
    }

    private void Update()
    {
        age += Time.deltaTime;
        float t = Mathf.Clamp01(age / duration);
        transform.localScale = Vector3.one * Mathf.Lerp(.25f, radius, t);
        if (age >= duration) Destroy(gameObject);
    }

}
