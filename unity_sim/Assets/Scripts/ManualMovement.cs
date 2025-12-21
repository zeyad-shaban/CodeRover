using System;
using UnityEngine;

public class ManualMovement : MonoBehaviour {
    private Brain brain;
    public event EventHandler<MoveEventArgs> OnMoveInput;

    public class MoveEventArgs : EventArgs {
        public float vLeft;
        public float vRight;
    }

    void Start() {
        brain = GetComponent<Brain>();
    }

    void Update() {
        float vLeft = 0;
        float vRight = 0;

        if (Input.GetKey(KeyCode.W)) {
            vLeft = brain.speed;
            vRight = brain.speed;
        }
        else if (Input.GetKey(KeyCode.S)) {
            vLeft = -brain.speed;
            vRight = -brain.speed;
        }
        if (Input.GetKey(KeyCode.A)) {
            vLeft *= 0.5f;
        }
        else if (Input.GetKey(KeyCode.D)) {
            vRight *= 0.5f;
        }

        OnMoveInput?.Invoke(this, new MoveEventArgs { vLeft = vLeft, vRight = vRight });
    }

}
