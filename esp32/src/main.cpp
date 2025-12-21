#include <Arduino.h>
#include <HardwareSerial.h>
#include <NimBLEDevice.h>

int txPin = 23;

int motR1Pin = 27;
int motR2Pin = 26;
int motL3Pin = 19;
int motL4Pin = 13;

const int freq = 5000;
int motRight1Chan = 0;
int motRight2Chan = 1;
int motLeft3Chan = 2;
int motLeft4Chan = 3;

unsigned long lastReceiveMillis = 0;
const unsigned long TIMEOUT_MS = 300;
bool stoppedByTimeout = false;

static const char *SERVICE_UUID = "12345678-1234-1234-1234-1234567890ab";
static const char *CHAR_UUID = "abcd1234-1234-1234-1234-abcdef123456";
static const char *BLE_NAME = "CarRover";

void updateMotors(float vLeft, float vRight, float signY);
void moveForward(float vLeft, float vRight);
void moveBackward(float vLeft, float vRight);
void stop();

class XYCallbacks : public NimBLECharacteristicCallbacks
{
  void onWrite(NimBLECharacteristic *pChar, NimBLEConnInfo &connInfo) override
  {
    std::string val = pChar->getValue();
    Serial.printf("Raw data received: %s\n", val.c_str());

    if (val.empty())
      return;

    float vLeft = 0.0f, vRight = 0.0f, signY = 0.0f, honk = 0.0f;
    if (sscanf(val.c_str(), "%f, %f, %f, %f", &vLeft, &vRight, &signY, &honk) >= 2)
    {
      Serial.printf("PARSED -> vLeft: %.3f  vRight: %.3f, signY: %f, honk: %f\n", vLeft, vRight, signY, honk);
      lastReceiveMillis = millis();
      stoppedByTimeout = false;

      updateMotors(vLeft, vRight, signY);

      // Honking
      if (honk >= 1)
        Serial2.println(honk);
    }
    else
    {
      Serial.printf("Format Error. String was: %s\n", val.c_str());
    }
  }
};

void setup()
{
  ledcSetup(motRight1Chan, freq, 8);
  ledcAttachPin(motR1Pin, motRight1Chan);

  ledcSetup(motRight2Chan, freq, 8);
  ledcAttachPin(motR2Pin, motRight2Chan);

  ledcSetup(motLeft3Chan, freq, 8);
  ledcAttachPin(motL3Pin, motLeft3Chan);

  ledcSetup(motLeft4Chan, freq, 8);
  ledcAttachPin(motL4Pin, motLeft4Chan);
  stop();

  Serial2.begin(115200, SERIAL_8N1, -1, txPin);
  Serial.begin(115200);
  delay(100);

  NimBLEDevice::init(BLE_NAME);
  NimBLEServer *server = NimBLEDevice::createServer();

  Serial.println("Setting up BLE service...");
  NimBLEService *service = server->createService(SERVICE_UUID);
  NimBLECharacteristic *chr = service->createCharacteristic(
      CHAR_UUID,
      NIMBLE_PROPERTY::WRITE_NR);

  chr->setCallbacks(new XYCallbacks());

  // Start service + advertising
  service->start();
  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setName(BLE_NAME);
  adv->enableScanResponse(true);
  adv->start();

  Serial.println("BLE server started");
  lastReceiveMillis = millis();
  stoppedByTimeout = false;

  uint64_t chipid = ESP.getEfuseMac();
  Serial.printf("ESP32 MAC Address: %04X%08X\n", (uint16_t)(chipid >> 32), (uint32_t)chipid);
  stop();
}

void loop()
{
  float now = millis();
  if (now - lastReceiveMillis >= TIMEOUT_MS)
  {
    if (!stoppedByTimeout)
    {
      Serial.printf("No commands for %lums -> stopping motors\n", now - lastReceiveMillis);
      stop();
      stoppedByTimeout = true;
    }
  }

  delay(5);
}

void updateMotors(float vLeft, float vRight, float signY)
{
  if (vLeft == 0 && vRight == 0)
    stop();
  else if (signY >= 0)
    moveForward(vLeft, vRight);
  else
    moveBackward(vLeft, vRight);
}

void moveForward(float vLeft, float vRight)
{
  // Right
  ledcWrite(motRight1Chan, 0);
  ledcWrite(motRight2Chan, vRight);

  // Left
  ledcWrite(motLeft3Chan, 0);
  ledcWrite(motLeft4Chan, vLeft);
}

void moveBackward(float vLeft, float vRight)
{
  // Right
  ledcWrite(motRight1Chan, vRight);
  ledcWrite(motRight2Chan, 0);

  // left
  ledcWrite(motLeft3Chan, vLeft);
  ledcWrite(motLeft4Chan, 0);
}

void stop()
{
  ledcWrite(motRight1Chan, 0);
  ledcWrite(motRight2Chan, 0);

  ledcWrite(motLeft3Chan, 0);
  ledcWrite(motLeft4Chan, 0);
}