# MAXSwerve 2024 AdvantageKit

## Overview
This is an AdvantageKit implementation of the REV MAXSwerve modules

## Customization
* Change CAN IDs to match your Spark MAX/FLEX controllers
* Change drive gear ratio to match your robot
* Tune Feedforward with built in autonomous routine (thanks Jonah!)
* Tune PID gains

## Feedforward Tuning Instructions
* set tuning mode is enabled in Constants.java:
```
public static final TUNING_MODE = true;
```
* Set up robot in a space where it has plenty of space to drive
* run ```FeedForwardCharacterization``` auto routine
* stop the auto before the robot collides with anything.
* the ```kS``` and ```kV``` gains will be printed to the console
* put those gains in for the ```REAL``` case in
```
DRIVE_KS.initDefault(<Your kS value>);
DRIVE_KV.initDefault(<Your kV value>);
```

## PID Tuning Note
It is recommended to use AdvantageScope while connected to the robot to tune your PID gains for driving and turning.