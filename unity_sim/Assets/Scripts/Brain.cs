using System;
using UnityEngine;

public class Brain : MonoBehaviour {
    [SerializeField] Transform target;
    [SerializeField] Transform carBase;
    [SerializeField] Transform carWheel;

    DiffrentialDriveController controller;
    ManualMovement manualMovement;

    [SerializeField] float Kp = 1.0f;
    private float baseWidth;
    private float wheelRadius;
    public float speed = 15;

    [SerializeField] bool isManual = false;


    private void Awake() {
        controller = GetComponent<DiffrentialDriveController>();
        manualMovement = GetComponent<ManualMovement>();

        baseWidth = carBase.localScale.x;
        wheelRadius = carWheel.localScale.x;
    }

    void OnEnable() {
        manualMovement.OnMoveInput += MoveInputHandler;
    }
    private void OnDisable() {
        manualMovement.OnMoveInput -= MoveInputHandler;

    }

    private void MoveInputHandler(object sender, ManualMovement.MoveEventArgs e) {
        if (isManual)
            controller.SetWheelSpeeds(e.vLeft, e.vRight);
    }

    private void Update() {
        if (isManual)
            return;

        Vector2 robotPos = new Vector2(transform.position.x, transform.position.z);
        Vector2 targetPos = new Vector2(target.position.x, target.position.z);

        float theta = Mathf.Atan2(transform.forward.z, transform.forward.x);
        Vector2 targetVec = targetPos - robotPos;
        targetVec *= Kp;

        float v_parallel = Mathf.Cos(theta) * targetVec.x + Mathf.Sin(theta) * targetVec.y;
        float v_perp = -Mathf.Sin(theta) * targetVec.x + Mathf.Cos(theta) * targetVec.y;

        float velocity = v_parallel;
        float omega = v_perp;

        float vRight = (velocity + (omega * baseWidth) / 2) / wheelRadius;
        float vLeft = (velocity - (omega * baseWidth) / 2) / wheelRadius;

        controller.SetWheelSpeeds(vLeft, vRight);
    }
}

