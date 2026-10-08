using UnityEngine;
using UnityEngine.EventSystems;

public sealed class RiftJoystick : MonoBehaviour, IPointerDownHandler, IDragHandler, IPointerUpHandler
{
    private RiftGame game;
    private RectTransform root;
    private RectTransform knob;

    public void Initialize(RiftGame owner, RectTransform joystickRoot, RectTransform joystickKnob)
    {
        game = owner;
        root = joystickRoot;
        knob = joystickKnob;
    }

    public void OnPointerDown(PointerEventData eventData) { UpdatePosition(eventData); }
    public void OnDrag(PointerEventData eventData) { UpdatePosition(eventData); }

    public void OnPointerUp(PointerEventData eventData)
    {
        if (knob != null) knob.anchoredPosition = Vector2.zero;
        if (game != null) game.SetMoveInput(Vector2.zero);
    }

    private void UpdatePosition(PointerEventData eventData)
    {
        if (root == null || knob == null || game == null) return;
        if (!RectTransformUtility.ScreenPointToLocalPointInRectangle(root, eventData.position, eventData.pressEventCamera, out Vector2 local)) return;
        float radius = Mathf.Min(root.rect.width, root.rect.height) * .38f;
        Vector2 normalized = Vector2.ClampMagnitude(local / Mathf.Max(1, radius), 1);
        knob.anchoredPosition = normalized * radius;
        game.SetMoveInput(normalized);
    }
}
