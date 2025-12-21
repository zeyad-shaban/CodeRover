using UnityEngine;

public class DiffrentialDriveController : MonoBehaviour {
    // V = (Vr + Vl) / 2
    // w = (Vr - Vl) / L

    [SerializeField] Transform carBody;
    private Rigidbody rb;

    private float baseWidth; // length between teh two base wheels
    private float Vright = 0;
    private float Vleft = 0;

    private float V = 0;
    private float W = 0;

    private float Kp = 1;

    void Start() {
        baseWidth = carBody.transform.localScale.z;
        rb = GetComponent<Rigidbody>();
    }

    public void SetWheelSpeeds(float left, float right) {
        Vleft = left;
        Vright = right;
    }


    void FixedUpdate() {
        V = (Vleft + Vright) * 0.5f * Kp;
        W = -(Vright - Vleft) / baseWidth;

        Vector3 dpos = transform.forward * V * Time.fixedDeltaTime;
        rb.MovePosition(rb.position + dpos);

        float deltaYaw = W * Mathf.Rad2Deg * Time.fixedDeltaTime;
        Quaternion deltaRot = Quaternion.Euler(0f, deltaYaw, 0f);
        rb.MoveRotation(rb.rotation * deltaRot);
    }
}
