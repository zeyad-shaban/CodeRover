#include <Arduino.h>
#include <SoftwareSerial.h>

const int rxPin = 12;
const int txPin = 11;
const int honkPin = 13;

SoftwareSerial mySerial(rxPin, txPin);
void honkOnce();

void setup()
{
  Serial.begin(115200);
  mySerial.begin(115200);
  pinMode(honkPin, OUTPUT);
  digitalWrite(honkPin, LOW);
  Serial.println("Arduino RX ready");
}

void loop()
{
  mySerial.listen();

  while (mySerial.available())
  {
    char c = mySerial.read();
    Serial.print("RX: ");
    Serial.println(c);

    if (c == '1')
    {
      honkOnce();
    }
  }
}

void honkOnce()
{
  Serial.println("i was called");
  digitalWrite(honkPin, HIGH);
  delay(200);
  digitalWrite(honkPin, LOW);
}
