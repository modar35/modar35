using UnityEngine;
using UnityEngine.EventSystems;

public sealed class RiftHoldButton : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IPointerExitHandler
{
    private RiftGame game;

    public void Initialize(RiftGame owner) { game = owner; }
    public void OnPointerDown(PointerEventData eventData) { if (game != null) game.SetAttackHeld(true); }
    public void OnPointerUp(PointerEventData eventData) { if (game != null) game.SetAttackHeld(false); }
    public void OnPointerExit(PointerEventData eventData) { if (game != null) game.SetAttackHeld(false); }
}
