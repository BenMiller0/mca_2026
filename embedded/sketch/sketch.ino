#include <Arduino_RouterBridge.h>
#include <Arduino_LED_Matrix.h>

#define SERVO_PIN 9
#define RED_LED 10
#define CUP_LED 11
#define SERVO_MIN 1000
#define SERVO_MAX 2000

ArduinoLEDMatrix matrix;

bool ledPulsing = false;
int ledPercent = 50;
bool ledOn = false;
bool voldemortMode = false;
int voldemortFrame = 0;

byte allOn[8][12] = {
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1},
  {1,1,1,1,1,1,1,1,1,1,1,1}
};

byte allOff[8][12] = {
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0}
};

// lightning bolt pattern for stupify
byte lightning[8][12] = {
  {0,0,0,0,1,1,0,0,0,0,0,0},
  {0,0,0,1,1,0,0,0,0,0,0,0},
  {0,0,0,1,1,1,1,0,0,0,0,0},
  {0,0,0,0,0,1,1,0,0,0,0,0},
  {0,0,0,0,1,1,1,1,0,0,0,0},
  {0,0,0,0,1,1,0,0,0,0,0,0},
  {0,0,0,1,1,0,0,0,0,0,0,0},
  {0,0,0,1,0,0,0,0,0,0,0,0}
};

// sun/glow pattern for lumos
byte lumos[8][12] = {
  {0,0,0,1,1,1,1,1,0,0,0,0},
  {0,0,1,1,1,1,1,1,1,0,0,0},
  {0,1,1,1,1,1,1,1,1,1,0,0},
  {0,1,1,1,1,1,1,1,1,1,0,0},
  {0,1,1,1,1,1,1,1,1,1,0,0},
  {0,1,1,1,1,1,1,1,1,1,0,0},
  {0,0,1,1,1,1,1,1,1,0,0,0},
  {0,0,0,1,1,1,1,1,0,0,0,0}
};

// skull pattern for voldemort
byte skull[8][12] = {
  {0,0,1,1,1,1,1,1,1,0,0,0},
  {0,1,1,0,1,1,0,1,1,1,0,0},
  {0,1,1,1,1,1,1,1,1,1,0,0},
  {0,1,1,0,1,0,1,0,1,1,0,0},
  {0,0,1,1,1,1,1,1,1,0,0,0},
  {0,0,0,1,1,0,1,1,0,0,0,0},
  {0,0,1,1,1,1,1,1,1,0,0,0},
  {0,0,0,0,0,0,0,0,0,0,0,0}
};

void servoWrite(int pin, int degrees) {
  int pulseUs = map(degrees, 0, 180, SERVO_MIN, SERVO_MAX);
  for (int i = 0; i < 50; i++) {
    digitalWrite(pin, HIGH);
    delayMicroseconds(pulseUs);
    digitalWrite(pin, LOW);
    delay(20);
  }
}

void blink_led(int pinNo, int option) {
  digitalWrite(pinNo, option);
}

void stopAllModes() {
  voldemortMode = false;
  voldemortFrame = 0;
  ledPulsing = false;
  matrix.renderBitmap(allOff, 8, 12);
  digitalWrite(RED_LED, LOW);
  digitalWrite(CUP_LED, LOW);
}

void cast_spell(String spellStr) {
  Serial.println("Got: " + spellStr);
  stopAllModes();
  int spell = spellStr.toInt();

  // Spell 1 - Stupify: lightning bolt flash, cup LED flash, servo sweep
  if (spell == 1) {
    // flash lightning bolt 3 times
    for (int i = 0; i < 3; i++) {
      matrix.renderBitmap(lightning, 8, 12);
      digitalWrite(CUP_LED, HIGH);
      delay(150);
      matrix.renderBitmap(allOff, 8, 12);
      digitalWrite(CUP_LED, LOW);
      delay(100);
    }
    // servo sweep
    servoWrite(SERVO_PIN, 180);
    delay(500);
    servoWrite(SERVO_PIN, 0);
    // leave lightning on
    matrix.renderBitmap(lightning, 8, 12);
  }

  // Spell 2 - Lumos/Nox: toggle red LED and glow pattern
  else if (spell == 2) {
    if (ledOn) {
      blink_led(RED_LED, LOW);
      matrix.renderBitmap(allOff, 8, 12);
      ledOn = false;
    } else {
      blink_led(RED_LED, HIGH);
      matrix.renderBitmap(lumos, 8, 12);
      ledOn = true;
    }
  }

  // Spell 3 - Voldemort: skull flash then chaos mode
  else if (spell == 3) {
    // flash skull 5 times before chaos
    for (int i = 0; i < 5; i++) {
      matrix.renderBitmap(skull, 8, 12);
      digitalWrite(RED_LED, HIGH);
      delay(200);
      matrix.renderBitmap(allOff, 8, 12);
      digitalWrite(RED_LED, LOW);
      delay(100);
    }
    voldemortMode = true;
    voldemortFrame = 0;
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(SERVO_PIN, OUTPUT);
  pinMode(RED_LED, OUTPUT);
  pinMode(CUP_LED, OUTPUT);
  matrix.begin();
  Bridge.begin();
  Bridge.provide("cast_spell", cast_spell);
  servoWrite(SERVO_PIN, 0);
  // boot animation
  matrix.renderBitmap(allOn, 8, 12);
  delay(500);
  matrix.renderBitmap(allOff, 8, 12);
}

void loop() {
  if (voldemortMode) {
    byte chaos[8][12];
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 12; c++) {
        chaos[r][c] = random(0, 2);
      }
    }
    matrix.renderBitmap(chaos, 8, 12);
    digitalWrite(RED_LED, random(0, 2));
    digitalWrite(CUP_LED, random(0, 2));
    delay(random(20, 80));
    voldemortFrame++;

    if (voldemortFrame > 3500) {
      voldemortMode = false;
      matrix.renderBitmap(skull, 8, 12);
      digitalWrite(RED_LED, HIGH);
    }
  } else if (ledPulsing) {
    int onTime = map(ledPercent, 0, 100, 0, 20);
    int offTime = 20 - onTime;
    digitalWrite(RED_LED, HIGH);
    delay(onTime);
    digitalWrite(RED_LED, LOW);
    delay(offTime);
  } else {
    delay(10);
  }
}